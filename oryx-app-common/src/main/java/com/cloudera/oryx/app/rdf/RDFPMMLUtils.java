/*
 * Copyright (c) 2014, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.oryx.app.rdf;

import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.common.base.Preconditions;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MiningFunctionType;
import org.dmg.pmml.MiningModel;
import org.dmg.pmml.Model;
import org.dmg.pmml.MultipleModelMethodType;
import org.dmg.pmml.Node;
import org.dmg.pmml.PMML;
import org.dmg.pmml.Predicate;
import org.dmg.pmml.ScoreDistribution;
import org.dmg.pmml.Segment;
import org.dmg.pmml.Segmentation;
import org.dmg.pmml.SimplePredicate;
import org.dmg.pmml.SimpleSetPredicate;
import org.dmg.pmml.TreeModel;
import org.dmg.pmml.True;

import com.cloudera.oryx.app.pmml.AppPMMLUtils;
import com.cloudera.oryx.app.rdf.decision.CategoricalDecision;
import com.cloudera.oryx.app.rdf.decision.Decision;
import com.cloudera.oryx.app.rdf.decision.NumericDecision;
import com.cloudera.oryx.app.rdf.predict.CategoricalPrediction;
import com.cloudera.oryx.app.rdf.predict.NumericPrediction;
import com.cloudera.oryx.app.rdf.predict.Prediction;
import com.cloudera.oryx.app.rdf.tree.DecisionForest;
import com.cloudera.oryx.app.rdf.tree.DecisionNode;
import com.cloudera.oryx.app.rdf.tree.DecisionTree;
import com.cloudera.oryx.app.rdf.tree.TerminalNode;
import com.cloudera.oryx.app.rdf.tree.TreeNode;
import com.cloudera.oryx.app.schema.CategoricalValueEncodings;
import com.cloudera.oryx.app.schema.InputSchema;
import com.cloudera.oryx.common.collection.Pair;
import com.cloudera.oryx.common.text.TextUtils;

public final class RDFPMMLUtils {

  private RDFPMMLUtils() {}

  /**
   * @param pmml PMML representation of decision forest
   * @param schema information about the configured, to validate the PMML and help parse it
   * @return a {@link DecisionForest} representation of the PMML encoded model
   */
  public static Pair<DecisionForest,CategoricalValueEncodings> read(PMML pmml, InputSchema schema) {

    CategoricalValueEncodings categoricalValueEncodings =
        AppPMMLUtils.buildCategoricalValueEncodings(pmml.getDataDictionary(), schema);

    List<Model> models = pmml.getModels();
    Preconditions.checkArgument(models.size() == 1);

    Model model = models.get(0);
    Preconditions.checkArgument(
        schema.isClassification() ==
        (model.getFunctionName() == MiningFunctionType.CLASSIFICATION));

    DecisionTree[] trees;
    double[] weights;
    if (model instanceof MiningModel) {
      MiningModel miningModel = (MiningModel) model;
      Segmentation segmentation = miningModel.getSegmentation();
      Preconditions.checkArgument(
          segmentation.getMultipleModelMethod() == MultipleModelMethodType.WEIGHTED_AVERAGE ||
          segmentation.getMultipleModelMethod() == MultipleModelMethodType.WEIGHTED_MAJORITY_VOTE);
      List<Segment> segments = segmentation.getSegments();
      Preconditions.checkArgument(!segments.isEmpty());

      trees = new DecisionTree[segments.size()];
      weights = new double[trees.length];
      for (int i = 0; i < trees.length; i++) {
        Segment segment = segments.get(i);
        Preconditions.checkArgument(segment.getPredicate() instanceof True);
        weights[i] = segment.getWeight();
        TreeModel treeModel = (TreeModel) segment.getModel();
        TreeNode root = translateFromPMML(treeModel.getNode(),
                                          categoricalValueEncodings,
                                          schema);
        trees[i] = new DecisionTree(root);
      }
    } else {
      // Single tree model
      TreeNode root = translateFromPMML(((TreeModel) model).getNode(),
                                        categoricalValueEncodings,
                                        schema);
      trees = new DecisionTree[] { new DecisionTree(root) };
      weights = new double[] { 1.0 };
    }

    List<String> featureNames = schema.getFeatureNames();
    List<MiningField> miningFields = model.getMiningSchema().getMiningFields();
    Preconditions.checkArgument(featureNames.size() == miningFields.size());
    double[] featureImportances = new double[featureNames.size()];
    for (int i = 0; i < miningFields.size(); i++) {
      MiningField field = miningFields.get(i);
      String fieldName = field.getName().getValue();
      Preconditions.checkArgument(fieldName.equals(featureNames.get(i)));
      Double importance = field.getImportance();
      if (importance != null) {
        int featureNumber = featureNames.indexOf(fieldName);
        featureImportances[featureNumber] = importance;
      }
    }

    return new Pair<>(new DecisionForest(trees, weights, featureImportances),
                      categoricalValueEncodings);
  }

  private static TreeNode translateFromPMML(Node root,
                                            CategoricalValueEncodings categoricalValueEncodings,
                                            InputSchema schema) {

    List<String> featureNames = schema.getFeatureNames();
    int targetFeature = schema.getTargetFeatureIndex();

    List<Node> children = root.getNodes();
    if (children.isEmpty()) {
      // Terminal
      Collection<ScoreDistribution> scoreDistributions = root.getScoreDistributions();
      Prediction prediction;
      if (scoreDistributions != null && !scoreDistributions.isEmpty()) {
        // Categorical target
        Map<String,Integer> valueEncoding =
            categoricalValueEncodings.getValueEncodingMap(targetFeature);
        int[] categoryCounts = new int[valueEncoding.size()];
        for (ScoreDistribution dist : scoreDistributions) {
          int encoding = valueEncoding.get(dist.getValue());
          categoryCounts[encoding] = (int) Math.round(dist.getRecordCount());
        }
        prediction = new CategoricalPrediction(categoryCounts);
      } else {
        prediction = new NumericPrediction(Double.parseDouble(root.getScore()),
                                           (int) Math.round(root.getRecordCount()));
      }
      return new TerminalNode(prediction);
    }

    Preconditions.checkArgument(children.size() == 2);
    // Decision
    Node child1 = children.get(0);
    Node child2 = children.get(1);
    Node negativeLeftChild;
    Node positiveRightChild;
    if (child1.getPredicate() instanceof True) {
      negativeLeftChild = child1;
      positiveRightChild = child2;
    } else {
      Preconditions.checkArgument(child2.getPredicate() instanceof True);
      negativeLeftChild = child2;
      positiveRightChild = child1;
    }

    Decision decision;
    Predicate predicate = positiveRightChild.getPredicate();
    boolean defaultDecision = positiveRightChild.getId().equals(root.getDefaultChild());

    if (predicate instanceof SimplePredicate) {
      // Numeric decision
      SimplePredicate simplePredicate = (SimplePredicate) predicate;
      SimplePredicate.Operator operator = simplePredicate.getOperator();
      Preconditions.checkArgument(
          operator == SimplePredicate.Operator.GREATER_OR_EQUAL ||
          operator == SimplePredicate.Operator.GREATER_THAN);
      double threshold = Double.parseDouble(simplePredicate.getValue());
      // NumericDecision uses >= criteria. Increase threshold by one ulp to implement
      // "> threshold" as ">= (threshold + ulp)"
      if (operator == SimplePredicate.Operator.GREATER_THAN) {
        threshold += Math.ulp(threshold);
      }
      int featureNumber = featureNames.indexOf(simplePredicate.getField().getValue());
      decision = new NumericDecision(featureNumber, threshold, defaultDecision);

    } else {
      // Categorical decision
      Preconditions.checkArgument(predicate instanceof SimpleSetPredicate);
      SimpleSetPredicate simpleSetPredicate = (SimpleSetPredicate) predicate;
      SimpleSetPredicate.BooleanOperator operator = simpleSetPredicate.getBooleanOperator();
      Preconditions.checkArgument(
          operator == SimpleSetPredicate.BooleanOperator.IS_IN ||
              operator == SimpleSetPredicate.BooleanOperator.IS_NOT_IN);
      int featureNumber = featureNames.indexOf(simpleSetPredicate.getField().getValue());
      Map<String,Integer> valueEncodingMap =
          categoricalValueEncodings.getValueEncodingMap(featureNumber);
      String[] categories = TextUtils.parseDelimited(simpleSetPredicate.getArray().getValue(), ' ');
      BitSet activeCategories = new BitSet(valueEncodingMap.size());
      if (operator == SimpleSetPredicate.BooleanOperator.IS_IN) {
        for (String category : categories) {
          activeCategories.set(valueEncodingMap.get(category));
        }
      } else {
        // "not in"
        for (int encoding : valueEncodingMap.values()) {
          activeCategories.set(encoding);
        }
        for (String category : categories) {
          activeCategories.clear(valueEncodingMap.get(category));
        }
      }
      decision = new CategoricalDecision(featureNumber, activeCategories, defaultDecision);
    }

    return new DecisionNode(
        decision,
        translateFromPMML(negativeLeftChild, categoricalValueEncodings, schema),
        translateFromPMML(positiveRightChild, categoricalValueEncodings, schema));
  }

}
