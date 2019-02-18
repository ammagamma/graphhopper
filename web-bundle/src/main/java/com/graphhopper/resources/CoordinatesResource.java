package com.graphhopper.resources;

import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.shapes.GHPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

@Path("")
public class CoordinatesResource {

    private static final Logger logger = LoggerFactory.getLogger(CoordinatesResource.class);
    private final GraphHopperStorage storage;

    @Inject
    public CoordinatesResource(GraphHopperStorage storage) {
        this.storage = storage;
    }

    @GET
    @Path("node-coordinate")
    @Produces({MediaType.APPLICATION_JSON})
    public GHPoint getNodeCoordinate(
            @QueryParam("nodeId") int nodeId
    ) {
        return new GHPoint(storage.getNodeAccess().getLat(nodeId), storage.getNodeAccess().getLon(nodeId));
    }

    @GET
    @Path("edge-coordinate")
    @Produces({MediaType.APPLICATION_JSON})
    public GHPoint getEdgeCoordinate(
            @QueryParam("edgeId") int edgeId
    ) {
        return getNodeCoordinate(storage.getSomeNodeId(edgeId));
    }

}
