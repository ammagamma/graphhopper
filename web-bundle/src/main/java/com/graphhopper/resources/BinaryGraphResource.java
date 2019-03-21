package com.graphhopper.resources;

import com.google.common.base.Stopwatch;
import com.graphhopper.graphtool.Point;
import com.graphhopper.routing.util.AllCHEdgesIterator;
import com.graphhopper.storage.CHGraphImpl;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.NodeAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

@Path("binary-graph")
public class BinaryGraphResource {

    private static final Logger logger = LoggerFactory.getLogger(BinaryGraphResource.class);
    private final GraphHopperStorage storage;

    @Inject
    public BinaryGraphResource(GraphHopperStorage storage) {
        this.storage = storage;
    }

    @GET
    @Produces({MediaType.APPLICATION_JSON})
    public Response doGet(
            @QueryParam("northEastLat") double northEastLat,
            @QueryParam("northEastLng") double northEastLng,
            @QueryParam("southWestLat") double southWestLat,
            @QueryParam("southWestLng") double southWestLng
    ) {
        Stopwatch sw = Stopwatch.createStarted();
        List<Number[]> edges = new ArrayList<>();
        NodeAccess na = storage.getNodeAccess();
        CHGraphImpl chGraph = storage.getGraph(CHGraphImpl.class);
        AllCHEdgesIterator iter = chGraph.getAllEdges();
        while (iter.next()) {
            int from = iter.getBaseNode();
            int to = iter.getAdjNode();
            Point pointFrom = new Point(from, chGraph.getLevel(from), na.getLat(from), na.getLon(from));
            Point pointTo = new Point(to, chGraph.getLevel(to), na.getLat(to), na.getLon(to));
            double maxLat = Math.max(pointFrom.lat, pointTo.lat);
            double maxLon = Math.max(pointFrom.lon, pointTo.lon);
            double minLat = Math.min(pointFrom.lat, pointTo.lat);
            double minLon = Math.min(pointFrom.lon, pointTo.lon);
            if (maxLat > northEastLat || maxLon > northEastLng || minLat < southWestLat || minLon < southWestLng) {
                continue;
            }

            if (!iter.isShortcut()) {
                edges.add(new Number[]{
                        iter.getEdge(), iter.getWeight(),
                        pointFrom.nodeId, pointFrom.level, pointFrom.lat, pointFrom.lon,
                        pointTo.nodeId, pointTo.level, pointTo.lat, pointTo.lon
                });
            }
        }

        int numFloatsPerEdge = 10;
        int numBytesPerFloat = 4;
        ByteBuffer bb = ByteBuffer.allocate(2 * numBytesPerFloat + edges.size() * numFloatsPerEdge * numBytesPerFloat);
        bb.putInt(edges.size());
        bb.putInt(numFloatsPerEdge * 4);
        for (Number[] edge : edges) {
            for (int e = 0; e < numFloatsPerEdge; e++) {
                bb.putFloat(edge[e].floatValue());
            }
        }

        logger.info("found " + edges.size() + " edges, stored " + numFloatsPerEdge + " floats per edge, took: " + sw);
        return Response.fromResponse(Response.ok(bb.array(), new MediaType("application", "octet-stream")).build())
                .header("X-GH-Took", "" + sw.toString())
                .build();
    }
}
