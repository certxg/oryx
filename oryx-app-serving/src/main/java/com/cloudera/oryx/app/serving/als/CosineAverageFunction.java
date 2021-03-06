/*
 * Copyright (c) 2014, Cloudera and Intel, Inc. Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.oryx.app.serving.als;

import com.cloudera.oryx.common.math.VectorMath;

final class CosineAverageFunction implements DoubleFunction<float[]> {

  private final float[][] itemFeatureVectors;

  CosineAverageFunction(float[][] itemFeatureVectors) {
    this.itemFeatureVectors = itemFeatureVectors;
  }

  @Override
  public double apply(float[] itemVector) {
    double total = 0.0;
    for (float[] itemFeatureVector : itemFeatureVectors) {
      double cosineSimilarity = VectorMath.dot(itemFeatureVector, itemVector) /
          (VectorMath.norm(itemFeatureVector) * VectorMath.norm(itemVector));
      total += cosineSimilarity;
    }
    return total / itemFeatureVectors.length;
  }
}
