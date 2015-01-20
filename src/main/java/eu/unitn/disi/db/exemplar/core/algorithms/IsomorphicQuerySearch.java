/*
 * Copyright (C) 2012 Matteo Lissandrini <ml at disi.unitn.eu>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package eu.unitn.disi.db.exemplar.core.algorithms;

import eu.unitn.disi.db.exemplar.core.algorithms.steps.GraphIsomorphismRecursiveStep;
import eu.unitn.disi.db.command.exceptions.AlgorithmExecutionException;
import eu.unitn.disi.db.command.util.StopWatch;
import eu.unitn.disi.db.exemplar.core.RelatedQuery;
import eu.unitn.disi.db.grava.graphs.BaseMultigraph;
import eu.unitn.disi.db.grava.graphs.Edge;
import eu.unitn.disi.db.grava.graphs.Multigraph;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * This class contains a naive - RECURSIVE - implementation for Algorithm1 thus
 * the solution obtained traversing the graph in order to find subgraphs
 * matching the pattern in the query given as input
 *
 * @author Matteo Lissandrini <ml at disi.unitn.eu>
 */
public class IsomorphicQuerySearch extends RelatedQuerySearch {

    /**
     * Execute the algorithm
     *
     * @throws AlgorithmExecutionException
     */
    @Override
    public void compute() throws AlgorithmExecutionException {
        Long startingNode = this.getRootNode(true);
        if (startingNode == null) {
            throw new AlgorithmExecutionException("no root node has been found, and this is plain WR0NG!");
        }

        // FreebaseConstants.convertLongToMid(
        // debug("Root node %s ", startingNode);
        if (this.getGraph().edgeSet().isEmpty()) {
            throw new AlgorithmExecutionException("NO KB Edges to find a root node!");
        }

        if (this.getQuery().edgeSet().isEmpty()) {
            throw new AlgorithmExecutionException("NO Query Edges to find a root node!");
        }
        this.setRelatedQueries(new LinkedList<RelatedQuery>());

        Multigraph graph = this.getGraph();
        Multigraph query = this.getQuery();

        Collection<Long> graphNodes;

        StopWatch watch = new StopWatch();
        watch.start();

        if (this.getQueryToGraphMap() == null) {
            graphNodes = graph.vertexSet();
        } else {
            graph = this.pruneGraph();
            graphNodes = ((Map<Long, Set<Long>>) this.getQueryToGraphMap()).get(startingNode);
        }

        List<RelatedQuery> tmp = null;

        //Start in parallel
        ExecutorService pool = Executors.newFixedThreadPool(this.getNumThreads());
        int chunkSize = (int) Math.round(graphNodes.size() / this.getNumThreads() + 0.5);
        List<Future<List<RelatedQuery>>> lists = new ArrayList<>();
        ////////////////////// USE 1 THREAD
        //chunkSize =  graphNodes.size();
        ////////////////////// USE 1 THREAD

        List<List<Long>> nodesChunks = new LinkedList<>();
        List<Long> tmpChunk = new LinkedList<>(); // NETBEANS!
        int count = 0, threadNum = 0;

        for (Long node : graphNodes) {
            //if (nodesSimilarity(queryConcept, node) > MIN_SIMILARITY) {
            if (count % chunkSize == 0) {
                tmpChunk = new LinkedList<>();
                nodesChunks.add(tmpChunk);

            }

            tmpChunk.add(node);
            count++;

            //} else {
            //     loggable.error("Similarity not satisfied..");
            //}
        }

        for (List<Long> chunk : nodesChunks) {
            threadNum++;
            GraphIsomorphismRecursiveStep graphI = new GraphIsomorphismRecursiveStep(threadNum, chunk.iterator(), startingNode, query, graph, this.isLimitedComputation());
            lists.add(pool.submit(graphI));
        }

        info("Number of Threads: %d/%d chunk size: %d Number of nodes %d", threadNum, this.getNumThreads(), chunkSize, graphNodes.size());
        //        if(graphNodes.size()==1){
        //            Long i = graphNodes.iterator().next();
        //            debug("The lucky node is %s ", i);
        //        }

        //Merge partial results
        try {
            for (Future<List<RelatedQuery>> list : lists) {
                tmp = list.get();
                if (tmp != null) {
                    //debug("Graph size: %d", smallGraph.vertexSet().size());
                    //                  //((List<RelatedQuery>)this.getRelatedQueries()).addAll(tmp);
                    List<RelatedQuery> rr = this.getRelatedQueries();
                    rr.addAll(tmp);

                }
            }
        } catch (InterruptedException | ExecutionException ex) {
            error(ex.toString());
        }

        watch.stop();
        info("Computed related in %dms", watch.getElapsedTimeMillis());
    }

    /**
     *
     * @return a pruned graph exploiting the Query To Graph Map
     * @throws AlgorithmExecutionException
     */
    public Multigraph pruneGraph() throws AlgorithmExecutionException {
        StopWatch watch = new StopWatch();
        watch.start();

        Map<Long, Set<Long>> queryToGraphMap = this.getQueryToGraphMap();
        Multigraph graph = this.getGraph();
        Multigraph query = this.getQuery();

        Collection<Edge> queryEdges = query.edgeSet();
        Collection<Edge> graphEdges = graph.edgeSet();

        Set<Long> goodNodes = new HashSet<>();

        Multigraph restricted = new BaseMultigraph(graph.edgeSet().size());

        Long tmpSrc, tmpDst;
        Edge tmpEdge;

        int removed = 0;

        for (Edge queryEdge : queryEdges) {
            tmpSrc = queryEdge.getSource();
            tmpDst = queryEdge.getDestination();

            if (!queryToGraphMap.containsKey(tmpSrc) || queryToGraphMap.get(tmpSrc).isEmpty()) {
                //TODO Long should be converted to redable
                throw new AlgorithmExecutionException("Query tables do not contain maps for the node " + tmpSrc);
            }

            if (!queryToGraphMap.containsKey(tmpDst) || queryToGraphMap.get(tmpDst).isEmpty()) {
                //TODO Long should be converted
                throw new AlgorithmExecutionException("Query tables do not contain maps for the node " + tmpDst);
            }

            goodNodes.addAll(queryToGraphMap.get(tmpSrc));
            goodNodes.addAll(queryToGraphMap.get(tmpDst));
        }

        for (Iterator<Edge> it = graphEdges.iterator(); it.hasNext();) {
            tmpEdge = it.next();
            if (goodNodes.contains(tmpEdge.getDestination()) && goodNodes.contains(tmpEdge.getSource())) {
                restricted.addVertex(tmpEdge.getSource());
                restricted.addVertex(tmpEdge.getDestination());
                restricted.addEdge(tmpEdge);
            } else {
                removed++;
            }
        }
        debug("kept %d, removed %d over %d edges non mapping edges in %dms", restricted.edgeSet().size(), removed, graphEdges.size(), watch.getElapsedTimeMillis());

        return restricted;
    }

}
