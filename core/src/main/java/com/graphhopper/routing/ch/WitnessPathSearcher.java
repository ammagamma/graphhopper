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
package com.graphhopper.routing.ch;

import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.graphhopper.apache.commons.collections.IntDoubleBinaryHeap;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.weighting.TurnWeighting;
import com.graphhopper.storage.CHGraph;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;

import java.util.Arrays;

import static com.graphhopper.util.EdgeIterator.NO_EDGE;
import static java.lang.Double.isInfinite;

/**
 * Helper class used to perform local witness path searches for graph preparation in edge-based Contraction Hierarchies.
 * <p>
 *     (source edge) -- s -- x -- t -- (target edge) 
 * Let x be a node to be contracted (the 'center node') and s and t neighboring un-contracted nodes of x that are
 * directly connected with x (via a normal edge or a shortcut). This class is used to examine the optimal path
 * between s and t in the graph of not yet contracted nodes. More precisely it looks at the minimal-weight-path from an
 * original edge incoming to s (the 'source edge') to an arbitrary original edge incoming to t where the turn-costs at t
 * onto a given original edge outgoing from t (the 'target edge') are also considered. This class is mainly used to
 * differentiate between the following two cases:
 * <p>
 * 1) The optimal path described above has finite weight and only consists of one edge from s to x, an arbitrary number
 * of loops at x, and one edge from x to t. This is called a 'bridge-path' here.
 * 2) The optimal path has infinite weight or it includes an edge from s to another node than x or an edge from another
 * node than x to t. This is called a 'witness-path'.
 * <p>
 * To find the optimal path an edge-based unidirectional Dijkstra algorithm is used that takes into account turn-costs.
 * The search can be initialized for a given source edge and node to be contracted x. Subsequent searches for different
 * target edges will keep on building the shortest path tree from previous searches. For the performance of edge-based
 * CH graph preparation it is crucial to limit the local witness path searches. However the search always needs to at
 * least find the best bridge-path if one exists. Therefore we may stop expanding edges when a certain amount of settled
 * edges is exceeded, but even then we still need to expand edges that could possibly yield a bridge-path and we may
 * only stop this when it is guaranteed that no bridge-path exists. Here we limit the maximum number of settled
 * edges during the search and determine this maximum number based on the statistics we collected during previous
 * searches.
 */
public class WitnessPathSearcher {
    private static final int NO_NODE = -1;

    // graph variables
    private final CHGraph chGraph;
    private final TurnWeighting turnWeighting;
    private final EdgeExplorer outEdgeExplorer;
    private final EdgeExplorer origInEdgeExplorer;
    private final int maxLevel;

    // general parameters affecting the number of found witnesses and the search time
    private final Config config;

    // variables of the current search
    private int sourceEdge;
    private int sourceNode;
    private int centerNode;
    private double bestPathWeight;
    private int bestPathIncEdge;
    private boolean bestPathIsBridgePath;
    private int numPotentialBridgePaths;
    private int numSettledEdges;
    private int numPolledEdges;

    // data structures used to build the shortest path tree
    // we allocate memory for all possible edge keys and keep track which ones have been discovered so far
    private double[] weights;
    private int[] edges;
    private int[] incEdges;
    private int[] parents;
    private int[] adjNodes;
    private boolean[] isPotentialBridgePaths;
    private IntObjectMap<CHEntry> initialEntryParents;
    private IntArrayList changedEdges;
    private IntDoubleBinaryHeap dijkstraHeap;

    // we keep track of the average number and distribution width of settled edges during the last searches to estimate
    // an appropriate maximum of settled edges for the next searches
    private int maxSettledEdges;
    private final OnFlyStatisticsCalculator settledEdgesStats = new OnFlyStatisticsCalculator();

    // statistics to analyze performance
    private final Stats currentBatchStats = new Stats();
    private final Stats totalStats = new Stats();

    public WitnessPathSearcher(GraphHopperStorage graph, CHGraph chGraph, TurnWeighting turnWeighting, Config config) {
        this.chGraph = chGraph;
        this.turnWeighting = turnWeighting;
        this.config = config;

        DefaultEdgeFilter inEdgeFilter = DefaultEdgeFilter.inEdges(turnWeighting.getFlagEncoder());
        DefaultEdgeFilter outEdgeFilter = DefaultEdgeFilter.outEdges(turnWeighting.getFlagEncoder());
        outEdgeExplorer = chGraph.createEdgeExplorer(outEdgeFilter);
        origInEdgeExplorer = graph.createEdgeExplorer(inEdgeFilter);
        maxLevel = chGraph.getNodes();

        maxSettledEdges = config.minimumMaxSettledEdges;
        int numOriginalEdges = graph.getBaseGraph().getAllEdges().length();
        initStorage(2 * numOriginalEdges);
        initCollections();
    }

    /**
     * Deletes the shortest path tree that has been found so far and initializes a new witness path search for a given
     * node to be contracted and search edge.
     *
     * @param centerNode the node to be contracted (x)
     * @param sourceNode the neighbor node from which the search starts (s)
     * @param sourceEdge the original edge incoming to s from which the search starts
     * @return the number of initial entries and always 0 if we can not directly reach the center node from the given
     * source edge, e.g. when turn costs at s do not allow this.
     */
    public int initSearch(int centerNode, int sourceNode, int sourceEdge) {
        reset();
        this.sourceEdge = sourceEdge;
        this.sourceNode = sourceNode;
        this.centerNode = centerNode;
        setInitialEntries(sourceNode, sourceEdge, centerNode);
        // if there is no entry that reaches the center node we won't need to search for any witnesses
        if (numPotentialBridgePaths < 1) {
            reset();
            return 0;
        }
        currentBatchStats.numSearches++;
        currentBatchStats.maxNumSettledEdges += maxSettledEdges;
        totalStats.numSearches++;
        totalStats.maxNumSettledEdges += maxSettledEdges;
        return dijkstraHeap.getSize();
    }

    /**
     * Runs a witness path search for a given target edge. Results of previous searches (the shortest path tree) are
     * reused and the previous search is extended if necessary. Note that you need to call
     * {@link #initSearch(int, int, int)} before calling this method to initialize the search.
     *
     * @param targetNode the neighbor node that should be reached by the path (t)
     * @param targetEdge the original edge outgoing from t where the search ends
     * @return the leaf shortest path tree entry (including all ancestor entries) ending in an edge incoming in t if a
     * 'bridge-path' (see above) has been found to be the optimal path or null if the optimal path is either a witness
     * path or no finite weight path starting with the search edge and leading to the target edge could be found at all.
     */
    public CHEntry runSearch(int targetNode, int targetEdge) {
        // if source and target are equal we already have a candidate for the best path: a simple turn from the source
        // to the target edge
        bestPathWeight = sourceNode == targetNode
                ? calcTurnWeight(sourceEdge, sourceNode, targetEdge)
                : Double.POSITIVE_INFINITY;
        bestPathIncEdge = NO_EDGE;
        bestPathIsBridgePath = false;

        // check if we can already reach the target from the shortest path tree we discovered so far
        EdgeIterator inIter = origInEdgeExplorer.setBaseNode(targetNode);
        while (inIter.next()) {
            final int incEdge = inIter.getLastOrigEdge();
            final int edgeKey = getEdgeKey(incEdge, targetNode);
            if (edges[edgeKey] != NO_EDGE) {
                updateBestPath(targetNode, targetEdge, edgeKey);
            }
        }

        // run dijkstra to find the optimal path
        while (!dijkstraHeap.isEmpty()) {
            if (numPotentialBridgePaths < 1 && (!bestPathIsBridgePath || isInfinite(bestPathWeight))) {
                // we have not found a connection to the target edge yet and there are no entries on the heap anymore 
                // that could yield a bridge-path
                break;
            }
            final int currKey = dijkstraHeap.peek_element();
            if (weights[currKey] > bestPathWeight) {
                // just reaching this edge is more expensive than the best path found so far including the turn costs
                // to reach the target edge -> we can stop
                // important: we only peeked so far, so we keep the entry for future searches
                break;
            }
            dijkstraHeap.poll_element();
            numPolledEdges++;
            currentBatchStats.numPolledEdges++;
            totalStats.numPolledEdges++;

            if (isPotentialBridgePaths[currKey]) {
                numPotentialBridgePaths--;
            }

            // after a certain amount of edges has been settled we only expand entries that might yield a bridge-path
            if (numSettledEdges > maxSettledEdges && !isPotentialBridgePaths[currKey]) {
                continue;
            }

            EdgeIterator iter = outEdgeExplorer.setBaseNode(adjNodes[currKey]);
            while (iter.next()) {
                if (isContracted(iter.getAdjNode())) {
                    continue;
                }
                // do not allow u-turns
                if (iter.getFirstOrigEdge() == incEdges[currKey]) {
                    continue;
                }
                double weight = turnWeighting.calcWeight(iter, false, incEdges[currKey]) + weights[currKey];
                if (isInfinite(weight)) {
                    continue;
                }
                boolean isPotentialBridgePath = this.isPotentialBridgePaths[currKey] && iter.getAdjNode() == centerNode;

                // dijkstra expansion: add or update current entries
                int key = getEdgeKey(iter.getLastOrigEdge(), iter.getAdjNode());
                if (edges[key] == NO_EDGE) {
                    setEntry(key, iter, weight, currKey, isPotentialBridgePath);
                    changedEdges.add(key);
                    dijkstraHeap.insert_(weight, key);
                    updateBestPath(targetNode, targetEdge, key);
                } else if (weight < weights[key]) {
                    updateEntry(key, iter, weight, currKey, isPotentialBridgePath);
                    dijkstraHeap.update_(weight, key);
                    updateBestPath(targetNode, targetEdge, key);
                }
            }
            numSettledEdges++;
            currentBatchStats.numSettledEdges++;
            totalStats.numSettledEdges++;
            // do not keep searching after target node has been expanded first time, should speed up contraction a bit but
            // leads to less witnesses being found.
//            if (adjNodes[currKey] == targetNode) {
//                break;
//            }
        }

        if (bestPathIsBridgePath) {
            int edgeKey = getEdgeKey(bestPathIncEdge, targetNode);
            CHEntry result = getEntryForKey(edgeKey);
            // prepend all ancestors
            CHEntry entry = result;
            while (parents[edgeKey] >= 0) {
                edgeKey = parents[edgeKey];
                CHEntry parent = getEntryForKey(edgeKey);
                entry.parent = parent;
                entry = parent;
            }
            entry.parent = initialEntryParents.get(parents[edgeKey]);
            return result;
        } else {
            return null;
        }
    }

    public String getStatisticsString() {
        return "last batch: " + currentBatchStats.toString() + "total: " + totalStats.toString();
    }

    public long getNumPolledEdges() {
        return numPolledEdges;
    }


    public long getTotalNumSearches() {
        return totalStats.numSearches;
    }

    public void resetStats() {
        currentBatchStats.reset();
    }

    private void initStorage(int numEntries) {
        weights = new double[numEntries];
        Arrays.fill(weights, Double.POSITIVE_INFINITY);

        edges = new int[numEntries];
        Arrays.fill(edges, NO_EDGE);

        incEdges = new int[numEntries];
        Arrays.fill(incEdges, NO_EDGE);

        parents = new int[numEntries];
        Arrays.fill(parents, NO_NODE);

        adjNodes = new int[numEntries];
        Arrays.fill(adjNodes, NO_NODE);

        isPotentialBridgePaths = new boolean[numEntries];
        Arrays.fill(isPotentialBridgePaths, false);
    }

    private void initCollections() {
        initialEntryParents = new IntObjectHashMap<>(10);
        changedEdges = new IntArrayList(1000);
        dijkstraHeap = new IntDoubleBinaryHeap(1000);
    }

    private void setInitialEntries(int sourceNode, int sourceEdge, int centerNode) {
        EdgeIterator outIter = outEdgeExplorer.setBaseNode(sourceNode);
        while (outIter.next()) {
            if (isContracted(outIter.getAdjNode())) {
                continue;
            }
            double turnWeight = calcTurnWeight(sourceEdge, sourceNode, outIter.getFirstOrigEdge());
            if (isInfinite(turnWeight)) {
                continue;
            }
            double edgeWeight = turnWeighting.calcWeight(outIter, false, NO_EDGE);
            double weight = turnWeight + edgeWeight;
            boolean isPotentialBridgePath = outIter.getAdjNode() == centerNode;
            int incEdge = outIter.getLastOrigEdge();
            int adjNode = outIter.getAdjNode();
            int key = getEdgeKey(incEdge, adjNode);
            int parentKey = -key - 1;
            // note that we 'misuse' the parent also to store initial turncost and the first original edge of this 
            // initial entry
            CHEntry parent = new CHEntry(
                    NO_EDGE,
                    outIter.getFirstOrigEdge(),
                    sourceNode, turnWeight);
            if (edges[key] == NO_EDGE) {
                // add new initial entry
                edges[key] = outIter.getEdge();
                incEdges[key] = incEdge;
                adjNodes[key] = adjNode;
                weights[key] = weight;
                parents[key] = parentKey;
                isPotentialBridgePaths[key] = isPotentialBridgePath;
                initialEntryParents.put(parentKey, parent);
                changedEdges.add(key);
            } else if (weight < weights[key]) {
                // update existing entry, there may be entries with the same adjNode and last original edge,
                // but we only need the one with the lowest weight
                edges[key] = outIter.getEdge();
                weights[key] = weight;
                parents[key] = parentKey;
                isPotentialBridgePaths[key] = isPotentialBridgePath;
                initialEntryParents.put(parentKey, parent);
            }
        }

        // now that we know which entries are actually needed we add them to the heap
        for (int i = 0; i < changedEdges.size(); ++i) {
            int key = changedEdges.get(i);
            if (isPotentialBridgePaths[key]) {
                numPotentialBridgePaths++;
            }
            dijkstraHeap.insert_(weights[key], key);
        }
    }

    private void reset() {
        updateMaxSettledEdges();
        numSettledEdges = 0;
        numPolledEdges = 0;
        numPotentialBridgePaths = 0;
        resetShortestPathTree();
    }

    private void updateMaxSettledEdges() {
        // we use the statistics of settled edges of a batch of previous witness path searches to dynamically 
        // approximate the number of settled edges in the next batch
        settledEdgesStats.addObservation(numSettledEdges);
        if (settledEdgesStats.getCount() == config.getSettledEdgeStatsResetInterval()) {
            maxSettledEdges = Math.max(
                    config.getMinimumMaxSettledEdges(),
                    (int) (settledEdgesStats.getMean() +
                            config.getSigmaFactor() * Math.sqrt(settledEdgesStats.getVariance()))
            );
            settledEdgesStats.reset();
        }
    }

    private void resetShortestPathTree() {
        for (int i = 0; i < changedEdges.size(); ++i) {
            resetEntry(changedEdges.get(i));
        }
        changedEdges.elementsCount = 0;
        initialEntryParents.clear();
        dijkstraHeap.clear();
    }

    private void updateBestPath(int targetNode, int targetEdge, int edgeKey) {
        // whenever we hit the target node we update the best path
        if (adjNodes[edgeKey] == targetNode) {
            double totalWeight = weights[edgeKey] + calcTurnWeight(incEdges[edgeKey], targetNode, targetEdge);
            // there is a path to the target so we know that there must be some parent. therefore a negative parent key
            // means that the parent is a root parent (a parent of an initial entry) and we did not go via the center
            // node.
            boolean isBridgePath = parents[edgeKey] >= 0 && isPotentialBridgePaths[parents[edgeKey]];
            // in case of equal weights we always prefer a witness path over a bridge-path
            double tolerance = isBridgePath ? 0 : 1.e-6;
            if (totalWeight - tolerance < bestPathWeight) {
                bestPathWeight = totalWeight;
                bestPathIncEdge = incEdges[edgeKey];
                bestPathIsBridgePath = isBridgePath;
            }
        }
    }

    private void setEntry(int key, EdgeIteratorState edge, double weight, int parent, boolean isPotentialBridgePath) {
        edges[key] = edge.getEdge();
        incEdges[key] = edge.getLastOrigEdge();
        adjNodes[key] = edge.getAdjNode();
        weights[key] = weight;
        parents[key] = parent;
        if (isPotentialBridgePath) {
            isPotentialBridgePaths[key] = true;
            numPotentialBridgePaths++;
        }
    }

    private void updateEntry(int key, EdgeIteratorState edge, double weight, int currKey, boolean isPotentialBridgePath) {
        edges[key] = edge.getEdge();
        weights[key] = weight;
        parents[key] = currKey;
        if (isPotentialBridgePath) {
            if (!isPotentialBridgePaths[key]) {
                numPotentialBridgePaths++;
            }
        } else {
            if (isPotentialBridgePaths[key]) {
                numPotentialBridgePaths--;
            }
        }
        isPotentialBridgePaths[key] = isPotentialBridgePath;
    }

    private void resetEntry(int key) {
        weights[key] = Double.POSITIVE_INFINITY;
        edges[key] = NO_EDGE;
        incEdges[key] = NO_EDGE;
        parents[key] = NO_NODE;
        adjNodes[key] = NO_NODE;
        isPotentialBridgePaths[key] = false;
    }

    private CHEntry getEntryForKey(int edgeKey) {
        return new CHEntry(edges[edgeKey], incEdges[edgeKey], adjNodes[edgeKey], weights[edgeKey]);
    }

    private int getEdgeKey(int edge, int adjNode) {
        return GHUtility.getEdgeKey(chGraph, edge, adjNode, false);
    }

    private double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
        if (inEdge == outEdge) {
            return Double.POSITIVE_INFINITY;
        }
        return turnWeighting.calcTurnWeight(inEdge, viaNode, outEdge);
    }

    private boolean isContracted(int node) {
        return chGraph.getLevel(node) != maxLevel;
    }

    static class Config {
        /**
         * Determines the maximum number of settled edges for the next search based on the mean number of settled edges and
         * the fluctuation in the previous searches. The higher this number the longer the search will last and the more
         * witness paths will be found. Assuming a normal distribution for example sigmaFactor = 2 means that about 95% of
         * the searches will be within the limit.
         */
        private double sigmaFactor = 3.0;
        private int minimumMaxSettledEdges = 100;
        private int settledEdgeStatsResetInterval = 10_000;

        public double getSigmaFactor() {
            return sigmaFactor;
        }

        public void setSigmaFactor(double sigmaFactor) {
            this.sigmaFactor = sigmaFactor;
        }

        public int getMinimumMaxSettledEdges() {
            return minimumMaxSettledEdges;
        }

        public void setMinimumMaxSettledEdges(int minimumMaxSettledEdges) {
            this.minimumMaxSettledEdges = minimumMaxSettledEdges;
        }

        public int getSettledEdgeStatsResetInterval() {
            return settledEdgeStatsResetInterval;
        }

        public void setSettledEdgeStatsResetInterval(int settledEdgeStatsResetInterval) {
            this.settledEdgeStatsResetInterval = settledEdgeStatsResetInterval;
        }
    }

    static class Stats {
        private long numSearches;
        private long numPolledEdges;
        private long numSettledEdges;
        private long maxNumSettledEdges;

        @Override
        public String toString() {
            return String.format(
                    "limit-exhaustion: %s %%, avg-settled: %s, avg-max-settled: %s, avg-polled-edges: %s",
                    quotient(numSettledEdges, maxNumSettledEdges * 100),
                    quotient(numSettledEdges, numSearches),
                    quotient(maxNumSettledEdges, numSearches),
                    quotient(numPolledEdges, numSearches));
        }

        private String quotient(long a, long b) {
            return b == 0 ? "NaN" : String.format("%5.1f", a / ((double) b));
        }

        void reset() {
            numSearches = 0;
            numPolledEdges = 0;
            numSettledEdges = 0;
            maxNumSettledEdges = 0;
        }

    }
}
