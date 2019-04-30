/*
 *******************************************************************************
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.eclipse.microprofile.lra.tck.participant.api;

import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.Forget;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.Status;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.eclipse.microprofile.lra.annotation.ws.rs.Leave;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_PARENT_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.tck.participant.api.NonParticipatingTckResource.SUPPORTS_PATH;
import static org.eclipse.microprofile.lra.tck.participant.api.NonParticipatingTckResource.TCK_NON_PARTICIPANT_RESOURCE_PATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

@ApplicationScoped
@Path(ContextTckResource.TCK_CONTEXT_RESOURCE_PATH)
public class ContextTckResource {
    public static final String TCK_CONTEXT_RESOURCE_PATH = "context-tck-resource";

    public static final String NEW_LRA_PATH = "/new-lra";
    public static final String REQUIRED_LRA_PATH = "/required-lra";
    public static final String NESTED_LRA_PATH = "/nested-lra";
    // resource path for testing that context on outbound and inbound calls made from an
    // method annotated with @LRA are spec compliant
    public static final String CONTEXT_CHECK_LRA_PATH = "/context-check-lra";
    public static final String LEAVE_PATH = "/leave";
    // resource path for reading and writing the participant status
    public static final String STATUS_PATH = "/status";
    // resource path for reading stats relating to a participant in the context of a single LRA
    public static final String METRIC_PATH = "/count";
    // resource path for clearing state
    public static final String RESET_PATH = "/reset";

    // headers for tests to indicate how the participant should respond to callbacks
    // from the LRA implementation (complete, compensate, forget and status)
    public static final String LRA_TCK_FAULT_TYPE_HEADER = "Lra-Tck-Fault-Type";
    public static final String LRA_TCK_FAULT_CODE_HEADER = "Lra-Tck-Fault-Status";

    // a header for tests to indicate which LRA stats are being queried/reset
    public static final String LRA_TCK_HTTP_CONTEXT_HEADER = "Lra-Tck-Context";

    private static final String REQUIRES_NEW_LRA_PATH = "/requires-new-lra";

    /**
     * A class to hold all of the metrics gathered in the context of a single LRA.
     * We need stats per LRA since a misbehaving test may leave an LRA in need of
     * recovery which means that the compensate/complete call will continue to be
     * called when subsequent tests run - ie it is not possible to fully tear down
     * a failing test.
     */
    private static class LRAMetric {
        String lraId;
        Map<String, AtomicInteger> metrics = Stream.of(
                new AbstractMap.SimpleEntry<>(LRA.Type.NESTED.name(), new AtomicInteger(0)),
                new AbstractMap.SimpleEntry<>(Complete.class.getName(), new AtomicInteger(0)),
                new AbstractMap.SimpleEntry<>(Compensate.class.getName(), new AtomicInteger(0)),
                new AbstractMap.SimpleEntry<>(Status.class.getName(), new AtomicInteger(0)),
                new AbstractMap.SimpleEntry<>(Forget.class.getName(), new AtomicInteger(0)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        LRAMetric(String lraId) {
            this.lraId = lraId;
        }

        void increment(String metric) {
            if (metrics.containsKey(metric)) {
                metrics.get(metric).incrementAndGet();
            }
        }

        int get(String metric) {
            if (metrics.containsKey(metric)) {
                return metrics.get(metric).get();
            }

            return -1;
        }

        void clear() {
            metrics.forEach((k, v) -> v.set(0));
        }
    }

    // maintain metrics on a per LRA basis
    private Map<String, LRAMetric> metrics = new HashMap<>();

    private void incrementMetric(String name, String lraId) {
        metrics.putIfAbsent(lraId, new LRAMetric(lraId));
        metrics.get(lraId).increment(name);
    }

    /**
     * An enum which controls the behaviour of participant when the
     * LRA spec implementation invokes the compensation, completion,
     * forget and status callbacks. It is used for testing the
     * implementation is spec compliant.
     */
    public enum EndPhase {
        ACCEPTED, // a participant reports that the complete/compensate is in progress
        FAILED, // a particant reports that is is unable to complete/compensate
        SUCCESS // clear the any injected behaviour
    }

    private EndPhase endPhase = EndPhase.SUCCESS;
    // control which status to return from participant callbacks
    private Response.Status endPhaseStatus = Response.Status.OK;
    private ParticipantStatus status = ParticipantStatus.Active;

    // update the injected behaviour
    private void setEndPhase(String faultType, int faultCode) {
        if (faultType == null) {
            endPhase = EndPhase.SUCCESS;
            endPhaseStatus = Response.Status.OK;
        } else {
            endPhase = EndPhase.valueOf(faultType);
            endPhaseStatus = Response.Status.fromStatusCode(faultCode);
        }
    }

    // reset any state in preparation for the next test
    @PUT
    @Path(RESET_PATH)
    public Response reset(@HeaderParam(LRA_TCK_HTTP_CONTEXT_HEADER) String lraId) {
        status = ParticipantStatus.Active;
        endPhase = EndPhase.SUCCESS;
        endPhaseStatus = Response.Status.OK;

        metrics.clear();

        return Response.ok().build();
    }

    // start a new LRA which remains active after the method returns
    @LRA(value = LRA.Type.REQUIRES_NEW, end = false)
    @PUT
    @Path(NEW_LRA_PATH)
    public Response newLRA(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) String lraId,
                           @HeaderParam(LRA_TCK_FAULT_TYPE_HEADER) String tckFaultType,
                           @HeaderParam(LRA_TCK_FAULT_CODE_HEADER) int tckFaultCode) {
        // check for a requests to inject particular behaviour
        setEndPhase(tckFaultType, tckFaultCode);

        return Response.ok().entity(lraId).build();
    }

    // end an existing LRA or start and end a new one
    @LRA(value = LRA.Type.REQUIRED)
    @PUT
    @Path(REQUIRED_LRA_PATH)
    public Response requiredLRA(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) String lraId,
                                @HeaderParam(LRA_TCK_FAULT_TYPE_HEADER) String tckFaultType,
                                @HeaderParam(LRA_TCK_FAULT_CODE_HEADER) int tckFaultCode) {
        setEndPhase(tckFaultType, tckFaultCode);

        return Response.ok().entity(lraId).build();
    }

    @LRA(value = LRA.Type.REQUIRES_NEW)
    @PUT
    @Path(REQUIRES_NEW_LRA_PATH)
    public Response requiresNew(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) String lraId) {
        return Response.ok().entity(lraId).build();
    }

    @LRA(value = LRA.Type.NESTED, end = false)
    @PUT
    @Path(NESTED_LRA_PATH)
    public Response nestedLRA(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) String nestedLRA,
                              @HeaderParam(LRA_HTTP_PARENT_CONTEXT_HEADER) String parentLRA) {
        return Response.ok().entity(nestedLRA + "," + parentLRA).build();
    }

    // test that outgoing calls do not affect the calling context
    @LRA(value = LRA.Type.REQUIRED)
    @PUT
    @Path(CONTEXT_CHECK_LRA_PATH)
    public Response contextCheck(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) String lraId) {
        String remote;
        String active = getActiveLRA();
        assertEquals("contextCheck1: incoming and active LRAs are different", lraId, active);

        // invoke a remote service which runs in its own context. Do not set the context header
        remote = restPutInvocation(null, REQUIRES_NEW_LRA_PATH, "");
        assertNotEquals("contextCheck2: the remote service should not have ran with the active context", remote, active);


        assertEquals("contextCheck2: after calling a remote service the active LRAs are different", active, getActiveLRA());

        // invoke a remote service which runs in its own context. Do set the context header
        remote = restPutInvocation(active, REQUIRES_NEW_LRA_PATH, "");
        assertNotEquals("contextCheck3: the remote service should not have ran with the active context", remote, active);
        assertEquals("contextCheck3: after calling a remote service the active LRAs are different", active, getActiveLRA());

        // invoke a remote service which runs in the callers context, ie do set the context header
        remote = restPutInvocation(active, TCK_NON_PARTICIPANT_RESOURCE_PATH, SUPPORTS_PATH, "");
        assertEquals("contextCheck4: the remote service should have ran with the active context", remote, active);
        assertEquals("contextCheck4: after calling a remote service the active LRAs is different", active, getActiveLRA());

        return Response.ok().entity(lraId).build();
    }

    /**
     * Query the current value of a metric in the context of an LRA
     * @param lraId the LRA that owns the metric
     * @param metric the name of the metric
     * @return the current value of the metric
     */
    @GET
    @Path(METRIC_PATH + "/{metric}")
    public Response count(@HeaderParam(LRA_TCK_HTTP_CONTEXT_HEADER) String lraId,
                          @PathParam("metric") String metric) {
        if (metrics.containsKey(lraId)) {
            return Response.ok(metrics.get(lraId).get(metric)).build();
        } else {
            return Response.ok(-1).build();
        }
    }

    @Leave
    @PUT
    @Path(LEAVE_PATH)
    public Response leave(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) String lraId) {
        return Response.ok().entity(lraId).build();
    }

    @PUT
    @Path("/compensate")
    @Compensate
    public Response compensateWork(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) String lraId,
                                   @HeaderParam(LRA_HTTP_PARENT_CONTEXT_HEADER) String parent)
            throws NotFoundException {
        incrementMetric(Compensate.class.getName(), lraId);
        if (parent != null) {
            incrementMetric(LRA.Type.NESTED.name(), parent);
        }

        return getEndPhaseResponse(false);
    }

    @PUT
    @Path("/complete")
    @Complete
    public Response completeWork(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) String lraId,
                                 @HeaderParam(LRA_HTTP_PARENT_CONTEXT_HEADER) String parent)
            throws NotFoundException {
        incrementMetric(Complete.class.getName(), lraId);
        if (parent != null) {
            incrementMetric(LRA.Type.NESTED.name(), parent);
        }

        return getEndPhaseResponse(true);
    }

    @Status
    @GET
    @Path(STATUS_PATH)
    public Response status(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) String lraId,
                           @HeaderParam(LRA_HTTP_PARENT_CONTEXT_HEADER) String parent) {
        incrementMetric(Status.class.getName(), lraId);
        if (parent != null) {
            incrementMetric(LRA.Type.NESTED.name(), parent);
        }

        return Response.status(endPhaseStatus).entity(status.name()).build();
    }

    @Forget
    @DELETE
    @Path("/forget")
    public Response forget(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) String lraId,
                           @HeaderParam(LRA_HTTP_PARENT_CONTEXT_HEADER) String parent) {
        incrementMetric(Forget.class.getName(), lraId);
        if (parent != null) {
            incrementMetric(LRA.Type.NESTED.name(), parent);
        }

        return Response.status(endPhaseStatus).entity(status.name()).build();
    }

    // clear any injected participant behaviour
    @PUT
    @Path(STATUS_PATH)
    public Response clearStatus(@HeaderParam(LRA_TCK_HTTP_CONTEXT_HEADER) String lraId,
                           @HeaderParam(LRA_TCK_FAULT_TYPE_HEADER) String tckFaultType) {
        switch (status) {
            case Compensating:
                status = ParticipantStatus.Compensated;
                break;
            case Completing:
                status = ParticipantStatus.Completed;
                break;
            default:
                break; // do nothing
        }

        endPhase = EndPhase.SUCCESS;
        endPhaseStatus = Response.Status.OK;

        return Response.ok().entity(status.name()).build();
    }

    // modify participant responses based on injected behaviour specified by the test
    private Response getEndPhaseResponse(boolean completing) {
        switch (endPhase) {
            case ACCEPTED:
                status = completing ? ParticipantStatus.Completing : ParticipantStatus.Compensating;
                return Response.status(Response.Status.ACCEPTED).entity(status.name()).build();
            case FAILED:
                status = completing ? ParticipantStatus.FailedToComplete : ParticipantStatus.FailedToCompensate;
                return Response.status(endPhaseStatus).entity(status.name()).build();
            case SUCCESS: /* FALLTHRU */
            default:
                status = completing ? ParticipantStatus.Completed : ParticipantStatus.Compensated;
                return Response.status(Response.Status.OK).entity(status.name()).build();
        }
    }

    /*
     * helper methods for testing how outgoing/incoming JAX-RS calls affect the notion of the current context
     */

    @Context
    private UriInfo context;

    private String getActiveLRA() {
        return restPutInvocation(null, TCK_NON_PARTICIPANT_RESOURCE_PATH, SUPPORTS_PATH, "");
    }

    private String restPutInvocation(String lraURI, String path, String bodyText) {
        return restPutInvocation(lraURI, TCK_CONTEXT_RESOURCE_PATH, path, bodyText);
    }

    private String restPutInvocation(String lraURI, String resource, String path, String bodyText) {
        String id = null;
        Invocation.Builder builder = ClientBuilder.newClient()
                .target(context.getBaseUri())
                .path(resource)
                .path(path)
                .request();
        if (lraURI != null) {
            builder.header(LRA_HTTP_CONTEXT_HEADER, lraURI);
        }

        Response response = builder.put(Entity.text(bodyText));

        if (response.hasEntity()) {
            id = response.readEntity(String.class);
        }

        try {
            if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                throw new WebApplicationException("Error on REST PUT for LRA '" + lraURI
                        + "' at path '" + path + "' and body '" + bodyText + "'", response);
            }
        } finally {
            response.close();
        }

        return id;
    }
}