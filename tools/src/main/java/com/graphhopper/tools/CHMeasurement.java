/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.tools;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.routing.ch.*;
import com.graphhopper.routing.lm.LMAlgoFactoryDecorator;
import com.graphhopper.storage.*;
import com.graphhopper.util.*;
import com.graphhopper.util.exceptions.ConnectionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.graphhopper.util.Parameters.Algorithms.ASTAR_BI;
import static com.graphhopper.util.Parameters.Algorithms.DIJKSTRA_BI;
import static java.lang.System.nanoTime;

public class CHMeasurement {
    private static final Logger LOGGER = LoggerFactory.getLogger(CHMeasurement.class);

    public static void main(String[] args) {
        testPerformanceAutomaticNodeOrdering(args);
    }

    /**
     * Parses a given osm file, contracts the graph and runs random routing queries on it. This is useful to test
     * the node contraction heuristics with regards to the performance of the automatic graph contraction (the node
     * contraction order determines how many and which shortcuts will be introduced) and the resulting query speed.
     * The queries are compared with a normal AStar search for comparison and to ensure correctness.
     */
    private static void testPerformanceAutomaticNodeOrdering(String[] args) {
        CmdArgs cmdArgs = CmdArgs.read(args);
        LOGGER.info("Running analysis with parameters {}", cmdArgs);
        String osmFile = cmdArgs.get("datareader.file", "local/maps/bremen-latest.osm.pbf");
        cmdArgs.put("datareader.file", osmFile);
        int periodicUpdates = cmdArgs.getInt("period_updates", 0);
        int lazyUpdates = cmdArgs.getInt("lazy_updates", 100);
        int neighborUpdates = cmdArgs.getInt("neighbor_updates", 0);
        int contractedNodes = cmdArgs.getInt("contract_nodes", 100);
        int logMessages = cmdArgs.getInt("log_messages", 5);
        int landmarks = cmdArgs.getInt("landmarks", 0);
        boolean cleanup = cmdArgs.getBool("cleanup", true);
        final boolean withTurnCosts = cmdArgs.getBool("with_turncosts", true);

        final GraphHopper graphHopper = new GraphHopperOSM();
        if (withTurnCosts) {
            cmdArgs.put("graph.flag_encoders", "car|turn_costs=true");
            cmdArgs.put("prepare.ch.weightings", "fastest");
            if (landmarks > 0) {
                cmdArgs.put("prepare.lm.weightings", "fastest");
                cmdArgs.put("prepare.lm.landmarks", landmarks);
            }
        } else {
            cmdArgs.put("graph.flag_encoders", "car");
            cmdArgs.put("prepare.ch.weightings", "no");
        }
        CHAlgoFactoryDecorator chDecorator = graphHopper.getCHFactoryDecorator();
        chDecorator.setDisablingAllowed(true);
        chDecorator.setPreparationPeriodicUpdates(periodicUpdates);
        chDecorator.setPreparationLazyUpdates(lazyUpdates);
        chDecorator.setPreparationNeighborUpdates(neighborUpdates);
        chDecorator.setPreparationContractedNodes(contractedNodes);
        chDecorator.setPreparationLogMessages(logMessages);

        LMAlgoFactoryDecorator lmDecorator = graphHopper.getLMFactoryDecorator();
        lmDecorator.setEnabled(true);
        lmDecorator.setDisablingAllowed(true);

        graphHopper.init(cmdArgs);

        if (cleanup) {
            graphHopper.clean();
        }

        StopWatch sw = new StopWatch();
        sw.start();
        graphHopper.importOrLoad();
        sw.stop();
        LOGGER.info("Import and preparation took {}s", sw.getMillis() / 1000);

        long seed = 456;
        int iterations = 1_000;
        runCompareTest(DIJKSTRA_BI, graphHopper, withTurnCosts, seed, iterations);
        runCompareTest(ASTAR_BI, graphHopper, withTurnCosts, seed, iterations);

        runPerformanceTest(DIJKSTRA_BI, graphHopper, withTurnCosts, seed, iterations);
        runPerformanceTest(ASTAR_BI, graphHopper, withTurnCosts, seed, iterations);

        if (landmarks > 0) {
            runPerformanceTest("lm", graphHopper, withTurnCosts, seed, iterations);
        }

        graphHopper.close();
    }

    private static void runCompareTest(final String algo, final GraphHopper graphHopper, final boolean withTurnCosts, long seed, final int iterations) {
        LOGGER.info("Running compare test for {}, using seed {}", algo, seed);
        Graph g = graphHopper.getGraphHopperStorage();
        final int numNodes = g.getNodes();
        final NodeAccess nodeAccess = g.getNodeAccess();
        final Random random = new Random(seed);

        MiniPerfTest compareTest = new MiniPerfTest() {
            long chTime = 0;
            long noChTime = 0;

            @Override
            public int doCalc(boolean warmup, int run) {
                if (!warmup && run % 100 == 0) {
                    LOGGER.info("Finished {} of {} runs. {}", run, iterations,
                            run > 0 ? String.format(" CH: %6.2fms, without CH: %6.2fms",
                                    chTime * 1.e-6 / run, noChTime * 1.e-6 / run) : "");
                }
                GHRequest req = buildRandomRequest(random, numNodes, nodeAccess);
                req.getHints().put(Parameters.Routing.EDGE_BASED, withTurnCosts);
                req.getHints().put(Parameters.CH.DISABLE, false);
                req.getHints().put(Parameters.Landmark.DISABLE, true);
                req.setAlgorithm(algo);
                long start = nanoTime();
                GHResponse chRoute = graphHopper.route(req);
                if (!warmup)
                    chTime += (nanoTime() - start);

                req.getHints().put(Parameters.CH.DISABLE, true);
                start = nanoTime();
                GHResponse nonChRoute = graphHopper.route(req);
                if (!warmup)
                    noChTime += nanoTime() - start;

                if (connectionNotFound(chRoute) && connectionNotFound(nonChRoute)) {
                    // random query was not well defined -> ignore
                    return 0;
                }

                if (!chRoute.getErrors().isEmpty() || !nonChRoute.getErrors().isEmpty()) {
                    LOGGER.warn("there were errors for {}: \n with CH: {} \n without CH: {}", algo, chRoute.getErrors(), nonChRoute.getErrors());
                    return chRoute.getErrors().size();
                }

                if (!chRoute.getBest().getPoints().equals(nonChRoute.getBest().getPoints())) {
                    // small negative deviations are due to weight truncation when shortcuts ar stored
                    double chWeight = chRoute.getBest().getRouteWeight();
                    double nonCHWeight = nonChRoute.getBest().getRouteWeight();
                    LOGGER.warn("error for {}: found different points for query from {} to {}, {}", algo,
                            req.getPoints().get(0).toShortString(), req.getPoints().get(1).toShortString(),
                            getWeightDifferenceString(chWeight, nonCHWeight));
                }
                return chRoute.getErrors().size();
            }
        };
        compareTest.setIterations(iterations).start();
    }

    private static void runPerformanceTest(final String algo, final GraphHopper graphHopper, final boolean withTurnCosts, long seed, final int iterations) {
        Graph g = graphHopper.getGraphHopperStorage();
        final int numNodes = g.getNodes();
        final NodeAccess nodeAccess = g.getNodeAccess();
        final Random random = new Random(seed);
        final boolean lm = "lm".equals(algo);

        LOGGER.info("Running performance test for {}, seed = {}", algo, seed);
        final long[] numVisitedNodes = {0};
        MiniPerfTest performanceTest = new MiniPerfTest() {
            private long queryTime;

            @Override
            public int doCalc(boolean warmup, int run) {
                if (!warmup && run % 100 == 0) {
                    LOGGER.info("Finished {} of {} runs. {}", run, iterations,
                            run > 0 ? String.format(" Time: %6.2fms", queryTime * 1.e-6 / run) : "");
                }
                GHRequest req = buildRandomRequest(random, numNodes, nodeAccess);
                req.getHints().put(Parameters.Routing.EDGE_BASED, withTurnCosts);
                req.getHints().put(Parameters.CH.DISABLE, lm);
                req.getHints().put(Parameters.Landmark.DISABLE, !lm);
                if (!lm) {
                    req.setAlgorithm(algo);
                } else {
                    req.getHints().put(Parameters.Landmark.ACTIVE_COUNT, "8");
                    req.setWeighting("fastest"); // why do we need this for lm, but not ch ?
                }
                long start = nanoTime();
                GHResponse route = graphHopper.route(req);
                numVisitedNodes[0] += route.getHints().getInt("visited_nodes.sum", 0);
                if (!warmup)
                    queryTime += nanoTime() - start;
                return getRealErrors(route).size();
            }
        };
        performanceTest.setIterations(iterations).start();
        if (performanceTest.getDummySum() > 0.01 * iterations) {
            throw new IllegalStateException("too many errors, probably something is wrong");
        }
        LOGGER.info("Average query time for {}: {}ms", algo, performanceTest.getMean());
        LOGGER.info("Visited nodes for {}: {}", algo, Helper.nf(numVisitedNodes[0]));
    }

    private static String getWeightDifferenceString(double weight1, double weight2) {
        return String.format("route weight: %.6f vs. %.6f (diff = %.6f)",
                weight1, weight2, (weight1 - weight2));
    }

    private static boolean connectionNotFound(GHResponse response) {
        for (Throwable t : response.getErrors()) {
            if (t instanceof ConnectionNotFoundException) {
                return true;
            }
        }
        return false;
    }

    private static List<Throwable> getRealErrors(GHResponse response) {
        List<Throwable> realErrors = new ArrayList<>();
        for (Throwable t : response.getErrors()) {
            if (!(t instanceof ConnectionNotFoundException)) {
                realErrors.add(t);
            }
        }
        return realErrors;
    }

    private static GHRequest buildRandomRequest(Random random, int numNodes, NodeAccess nodeAccess) {
        int from = random.nextInt(numNodes);
        int to = random.nextInt(numNodes);
        double fromLat = nodeAccess.getLat(from);
        double fromLon = nodeAccess.getLon(from);
        double toLat = nodeAccess.getLat(to);
        double toLon = nodeAccess.getLon(to);
        return new GHRequest(fromLat, fromLon, toLat, toLon);
    }

}
