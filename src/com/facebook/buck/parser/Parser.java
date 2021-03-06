/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.parser;

import com.facebook.buck.debug.Tracer;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilder;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.annotation.Nullable;

public final class Parser {

  private static final Logger logger = Logger.getLogger(Parser.class.getCanonicalName());

  private final Ansi ansi;

  private final Map<BuildRuleType, BuildRuleFactory> ruleTypeToFactoryMap;

  private final BuildTargetParser buildTargetParser;

  /**
   * The build files that have been parsed and whose build rules are in {@link #knownBuildTargets}.
   */
  private final Set<File> parsedBuildFiles;

  /**
   * We parse a build file in search for one particular rule; however, we also keep track of the
   * other rules that were also parsed from it.
   */
  private final Map<String, BuildRuleBuilder> knownBuildTargets;


  private final String absolutePathToProjectRoot;
  private final ProjectFilesystem projectFilesystem;
  private final BuildFileTree buildFiles;

  private boolean parserWasPopulatedViaParseRawRules = false;

  public Parser(ProjectFilesystem projectFilesystem,
      BuildFileTree buildFiles,
      Ansi ansi) {
    this(projectFilesystem,
        buildFiles,
        ansi,
        new BuildTargetParser(projectFilesystem),
        Maps.<String, BuildRuleBuilder>newHashMap());
  }

  @VisibleForTesting
  Parser(ProjectFilesystem projectFilesystem,
         BuildFileTree buildFiles,
         Ansi ansi,
         BuildTargetParser buildTargetParser,
         Map<String, BuildRuleBuilder> knownBuildTargets) {
    this.projectFilesystem = projectFilesystem;
    this.buildFiles = Preconditions.checkNotNull(buildFiles);
    this.ansi = Preconditions.checkNotNull(ansi);

    this.knownBuildTargets = Preconditions.checkNotNull(knownBuildTargets);
    this.ruleTypeToFactoryMap = getRuleTypeToFactoryMap();

    this.buildTargetParser = Preconditions.checkNotNull(buildTargetParser);
    this.parsedBuildFiles = Sets.newHashSet();
    this.absolutePathToProjectRoot = projectFilesystem.getProjectRoot().getAbsolutePath();
  }

  public BuildTargetParser getBuildTargetParser() {
    return buildTargetParser;
  }

  public DependencyGraph parseBuildFilesForTargets(
      Iterable<BuildTarget> buildTargets,
      Iterable<String> defaultIncludes)
      throws IOException, NoSuchBuildTargetException {
    // Make sure that knownBuildTargets is initially populated with the BuildRuleBuilders for the
    // seed BuildTargets for the traversal.
    if (!parserWasPopulatedViaParseRawRules) {
      Set<File> buildTargetFiles = Sets.newHashSet();
      for (BuildTarget buildTarget : buildTargets) {
        File buildFile = buildTarget.getBuildFile();
        boolean isNewElement = buildTargetFiles.add(buildFile);
        if (isNewElement) {
          parseBuildFile(buildFile, defaultIncludes);
        }
      }
    }

    DependencyGraph graph = findAllTransitiveDependencies(buildTargets, defaultIncludes);
    Tracer.addComment("All build files parsed and dependency graph constructed.");
    return graph;
  }

  /**
   * @param toExplore BuildTargets whose dependencies need to be explored.
   */
  @VisibleForTesting
  DependencyGraph findAllTransitiveDependencies(
      Iterable<BuildTarget> toExplore,
      final Iterable<String> defaultIncludes) {
    final Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();
    final MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<BuildRule>();

    AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget> traversal =
        new AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget>() {

          @Override
          protected Iterator<BuildTarget> findChildren(BuildTarget buildTarget) {
            BuildRuleBuilder buildRuleBuilder = knownBuildTargets.get(
                buildTarget.getFullyQualifiedName());
            ParseContext parseContext = ParseContext.forBaseName(buildTarget.getBaseName());
            Set<BuildTarget> deps = Sets.newHashSet();
            for (String dep : buildRuleBuilder.getDeps()) {
              try {
                BuildTarget buildTargetForDep = buildTargetParser.parse(dep, parseContext);
                if (!knownBuildTargets.containsKey(buildTargetForDep.getFullyQualifiedName())) {
                  parseBuildFileContainingTarget(buildTargetForDep, defaultIncludes);
                }
                deps.add(buildTargetForDep);
              } catch (NoSuchBuildTargetException e) {
                throw new HumanReadableException(e);
              } catch (IOException e) {
                Throwables.propagate(e);
              }
            }

            return deps.iterator();
          }

          @Override
          protected void onNodeExplored(BuildTarget buildTarget) {
            String fullyQualifiedName = buildTarget.getFullyQualifiedName();
            BuildRuleBuilder builderForTarget = knownBuildTargets.get(fullyQualifiedName);
            BuildRule buildRule = builderForTarget.build(buildRuleIndex);

            // Update the graph.
            if (buildRule.getDeps().isEmpty()) {
              // If a build rule with no deps is specified as the build target to build, then make sure
              // it is in the graph.
              graph.addNode(buildRule);
            } else {
              for (BuildRule dep : buildRule.getDeps()) {
                graph.addEdge(buildRule, dep);
              }
            }

            buildRuleIndex.put(fullyQualifiedName, buildRule);
          }

          @Override
          protected void onTraversalComplete(
              Iterable<BuildTarget> nodesInExplorationOrder) {
          }
    };

    try {
      traversal.traverse(toExplore);
    } catch (AbstractAcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new HumanReadableException(e.getMessage());
    }

    return new DependencyGraph(graph);
  }

  /**
   * Note that if this Parser is populated via {@link #parseRawRules(List, RawRulePredicate)},
   * then this method should not be called.
   */
  private void parseBuildFileContainingTarget(
      BuildTarget buildTarget, Iterable<String> defaultIncludes)
      throws IOException, NoSuchBuildTargetException {
    if (parserWasPopulatedViaParseRawRules) {
      // In this case, all of the build rules should have been loaded into the knownBuildTargets
      // Map before this method was invoked. Therefore, there should not be any more build files to
      // parse. This must be the result of traversing a non-existent dep in a build rule, so an
      // error is reported to the user. Unfortunately, the source of the build file where the
      // non-existent rule was declared is not known at this point, which is why it is not included
      // in the error message.
      throw new HumanReadableException("No such build target: %s.", buildTarget);
    }

    File buildFile = buildTarget.getBuildFile();
    if (parsedBuildFiles.contains(buildFile)) {
      throw new HumanReadableException(
          "The build file that should contain %s has already been parsed (%s), " +
              "but %s was not found. Please make sure that %s is defined in %s.",
          buildTarget,
          buildFile,
          buildTarget,
          buildTarget,
          buildFile);
    }

    parseBuildFile(buildFile, defaultIncludes);
  }

  private void parseBuildFile(File buildFile, Iterable<String> defaultIncludes)
      throws IOException, NoSuchBuildTargetException {
    logger.info(String.format("Parsing %s file: %s", BuckConstant.BUILD_RULES_FILE_NAME, buildFile));
    List<Map<String, Object>> rules = com.facebook.buck.json.BuildFileToJsonParser.getAllRules(
        absolutePathToProjectRoot, Optional.of(buildFile.getPath()), defaultIncludes, ansi);
    parseRawRulesInternal(rules, null /* filter */, buildFile);

    parsedBuildFiles.add(buildFile);
  }

  /**
   * Populates the collection of known build targets that this Parser will use to construct a
   * dependency graph.
   * @param rules a list of raw data objects, each of which represents a build rule parsed from a
   *     build file
   * @param filter if specified, applied to each rule in rules. All matching rules will be included
   *     in the List returned by this method. If filter is null, then this method returns null.
   */
  @Nullable
  public List<BuildTarget> parseRawRules(List<Map<String, Object>> rules,
      @Nullable RawRulePredicate filter) throws NoSuchBuildTargetException {
    this.parserWasPopulatedViaParseRawRules = true;
    return parseRawRulesInternal(rules, filter, /* source */ null);
  }

  @Nullable
  private List<BuildTarget> parseRawRulesInternal(List<Map<String, Object>> rules,
      @Nullable RawRulePredicate filter,
      @Nullable File source) throws NoSuchBuildTargetException {
    List<BuildTarget> matchingTargets = (filter == null) ? null : Lists.<BuildTarget>newArrayList();

    for (Map<String, Object> map : rules) {
      String type = (String)map.get("type");
      BuildRuleType buildRuleType = BuildRuleType.valueOf(type.toUpperCase());
      BuildRuleFactory factory = ruleTypeToFactoryMap.get(buildRuleType);
      if (factory == null) {
        throw new RuntimeException("Unrecognized rule: " + type);
      }

      String name = (String)map.get("name");
      String basePath = (String)map.get("buck_base_path");
      File sourceOfBuildTarget;
      if (source == null) {
        String relativePathToBuildFile = !basePath.isEmpty()
            ? "./" + basePath + "/" + BuckConstant.BUILD_RULES_FILE_NAME
            : "./" + BuckConstant.BUILD_RULES_FILE_NAME;
        sourceOfBuildTarget = new File(relativePathToBuildFile);
      } else {
        sourceOfBuildTarget = source;
      }
      BuildTarget target = new BuildTarget(sourceOfBuildTarget, "//" + basePath, name);

      if (filter != null && filter.isMatch(map, buildRuleType, target)) {
        matchingTargets.add(target);
      }

      BuildRuleBuilder buildRuleBuilder = factory.newInstance(new BuildRuleFactoryParams(
          map,
          System.err, // TODO(simons): Injecting a Console instance turns out to be a nightmare.
          projectFilesystem,
          buildFiles,
          buildTargetParser,
          target));
      Object existingRule = knownBuildTargets.put(target.getFullyQualifiedName(), buildRuleBuilder);
      if (existingRule != null) {
        throw new RuntimeException("Duplicate definition for " + target.getFullyQualifiedName());
      }
    }

    return matchingTargets;
  }

  // TODO(mbolin): This will ultimately have to support a plug-in model so users can define their
  // own build rules.
  private Map<BuildRuleType, BuildRuleFactory> getRuleTypeToFactoryMap() {
    return ImmutableMap.<BuildRuleType, BuildRuleFactory>builder()
        .put(BuildRuleType.PREBUILT_JAR,
            new PrebuiltJarBuildRuleFactory())
        .put(BuildRuleType.JAVA_LIBRARY,
            new JavaLibraryBuildRuleFactory())
        .put(BuildRuleType.JAVA_TEST,
            new JavaTestBuildRuleFactory())
        .put(BuildRuleType.JAVA_BINARY,
            new JavaBinaryBuildRuleFactory())
        .put(BuildRuleType.NDK_LIBRARY,
            new NdkLibraryBuildRuleFactory())
        .put(BuildRuleType.ANDROID_BINARY, new AndroidBinaryBuildRuleFactory())
        .put(BuildRuleType.ANDROID_INSTRUMENTATION_APK, new AndroidInstrumentationApkRuleFactory())
        .put(BuildRuleType.ANDROID_LIBRARY, new AndroidLibraryBuildRuleFactory())
        .put(BuildRuleType.ANDROID_RESOURCE, new AndroidResourceBuildRuleFactory())
        .put(BuildRuleType.EXPORT_FILE, new ExportFileBuildRuleFactory())
        .put(BuildRuleType.PREBUILT_NATIVE_LIBRARY, new PrebuiltNativeLibraryBuildRuleFactory())
        .put(BuildRuleType.PROJECT_CONFIG, new ProjectConfigRuleFactory())
        .put(BuildRuleType.GEN_AIDL, new GenAidlBuildRuleFactory())
        .put(BuildRuleType.GEN_PARCELABLE, new GenParcelableBuildRuleFactory())
        .put(BuildRuleType.APK_GENRULE, new ApkGenruleBuildRuleFactory())
        .put(BuildRuleType.ANDROID_MANIFEST, new AndroidManifestBuildRuleFactory())
        .put(BuildRuleType.GENRULE, new GenruleBuildRuleFactory())
        .put(BuildRuleType.PYTHON_LIBRARY, new PythonLibraryBuildRuleFactory())
        .put(BuildRuleType.PYTHON_BINARY, new PythonBinaryBuildRuleFactory())
        .put(BuildRuleType.ROBOLECTRIC_TEST, new RobolectricTestBuildRuleFactory())
        .put(BuildRuleType.SH_TEST, new ShTestBuildRuleFactory())
        .build();
  }
}
