/*
 * Copyright 2019 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp.disambiguate;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static java.util.Comparator.comparingInt;
import static java.util.Comparator.naturalOrder;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.MultimapBuilder;
import com.google.gson.Gson;
import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.InvalidatingTypes;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.diagnostic.LogFile;
import com.google.javascript.jscomp.graph.DiGraph;
import com.google.javascript.jscomp.graph.DiGraph.DiGraphEdge;
import com.google.javascript.jscomp.graph.DiGraph.DiGraphNode;
import com.google.javascript.jscomp.graph.FixedPointGraphTraversal;
import com.google.javascript.jscomp.graph.LowestCommonAncestorFinder;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.jstype.JSTypeRegistry;
import java.util.Map;

/** Assembles the various parts of the diambiguator to execute them as a compiler pass. */
public final class DisambiguateProperties2 implements CompilerPass {

  private static final Gson GSON = new Gson();

  private final AbstractCompiler compiler;
  private final ImmutableMap<String, CheckLevel> invalidationReportingLevelByProp;

  private final JSTypeRegistry registry;
  private final InvalidatingTypes invalidations;

  public DisambiguateProperties2(
      AbstractCompiler compiler,
      ImmutableMap<String, CheckLevel> invalidationReportingLevelByProp) {
    this.compiler = compiler;
    this.invalidationReportingLevelByProp = invalidationReportingLevelByProp;

    this.registry = this.compiler.getTypeRegistry();
    this.invalidations =
        new InvalidatingTypes.Builder(this.registry)
            .addTypesInvalidForPropertyRenaming()
            .addAllTypeMismatches(compiler.getTypeMismatches())
            .addAllTypeMismatches(compiler.getImplicitInterfaceUses())
            .allowEnumsAndScalars()
            .build();
  }

  @Override
  public void process(Node externs, Node root) {
    checkArgument(externs.getParent() == root.getParent());

    TypeFlattener flattener = new TypeFlattener(this.registry, this.invalidations::isInvalidating);
    FindPropertyReferences findRefs =
        new FindPropertyReferences(
            flattener,
            /* errorCb= */ this.compiler::report,
            this.compiler.getCodingConvention()::isPropertyRenameFunction);
    TypeGraphBuilder graphBuilder =
        new TypeGraphBuilder(flattener, LowestCommonAncestorFinder::new);
    ClusterPropagator propagator = new ClusterPropagator();
    UseSiteRenamer renamer =
        new UseSiteRenamer(
            this.invalidationReportingLevelByProp,
            /* errorCb= */ this.compiler::report,
            /* mutationCb= */ this.compiler::reportChangeToEnclosingScope);

    NodeTraversal.traverse(this.compiler, externs.getParent(), findRefs);
    Map<String, PropertyClustering> propIndex = findRefs.getPropertyIndex();
    this.logForDiagnostics(
        "prop_refs",
        () ->
            propIndex.values().stream()
                .map(PropertyReferenceIndexJson::new)
                .collect(toImmutableSortedMap(naturalOrder(), (x) -> x.name, (x) -> x)));

    flattener.getAllKnownTypes().forEach(graphBuilder::add);
    DiGraph<FlatType, Object> graph = graphBuilder.build();
    this.logForDiagnostics(
        "graph",
        () ->
            graph.getNodes().stream()
                .map(TypeNodeJson::new)
                .sorted(comparingInt((x) -> x.id))
                .collect(toImmutableList()));

    FixedPointGraphTraversal.newTraversal(propagator).computeFixedPoint(graph);
    propIndex.values().forEach(renamer::renameUses);
    this.logForDiagnostics(
        "renaming_index",
        () -> MultimapBuilder.treeKeys().treeSetValues().build(renamer.getRenamingIndex()));
  }

  private void logForDiagnostics(String name, Supplier<Object> data) {
    try (LogFile log = this.compiler.createOrReopenLog(this.getClass(), name + ".log")) {
      log.log(() -> GSON.toJson(data.get()));
    }
  }

  private static String locationOf(Node n) {
    return n.getSourceFileName() + ":" + n.getLineno() + ":" + n.getCharno();
  }

  private static class PropertyReferenceIndexJson {
    final String name;
    final ImmutableSortedMap<String, Integer> refs;

    PropertyReferenceIndexJson(PropertyClustering prop) {
      this.name = prop.getName();

      ImmutableSortedMap.Builder<String, Integer> refs = ImmutableSortedMap.naturalOrder();
      prop.getUseSites().forEach((node, type) -> refs.put(locationOf(node), type.getId()));
      this.refs = refs.build();
    }
  }

  private static class TypeNodeJson {
    final int id;
    final boolean invalidating;
    final String name;
    final ImmutableSortedSet<TypeEdgeJson> edges;
    final ImmutableSortedSet<String> props;

    TypeNodeJson(DiGraphNode<FlatType, Object> n) {
      FlatType t = n.getValue();

      this.id = t.getId();
      this.invalidating = t.isInvalidating();
      this.name = t.getType().toString();
      this.edges =
          n.getOutEdges().stream()
              .map(TypeEdgeJson::new)
              .collect(toImmutableSortedSet(comparingInt((x) -> x.dest)));
      this.props =
          t.getAssociatedProps().stream()
              .map(PropertyClustering::getName)
              .collect(toImmutableSortedSet(naturalOrder()));
    }
  }

  private static class TypeEdgeJson {
    final int dest;
    final Object value;

    TypeEdgeJson(DiGraphEdge<FlatType, Object> e) {
      this.dest = e.getDestination().getValue().getId();
      this.value = e.getValue();
    }
  }
}
