/*
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.algorithm

import com.linkedin.photon.ml.constants.{MathConst, StorageLevel}
import com.linkedin.photon.ml.data.{DataSet, GameDatum}
import com.linkedin.photon.ml.evaluation.Evaluator
import com.linkedin.photon.ml.model.GAMEModel
import com.linkedin.photon.ml.util.{ObjectiveFunctionValue, PhotonLogger, Timer}
import com.linkedin.photon.ml.{BroadcastLike, RDDLike}
import org.apache.spark.rdd.RDD

/**
 * Coordinate descent implementation
 *
 * @param coordinates The individual optimization problem coordinates. The coordinates are a [[Seq]] of
 *                    (coordinateName, [[Coordinate]] object) pairs.
 * @param trainingLossFunctionEvaluator Training loss function evaluator
 * @param validatingDataAndEvaluatorsOption Optional validation data and evaluator. The validating data are a [[RDD]]
 *                                          of (uniqueId, [[GameDatum]] object pairs), where uniqueId is a unique
 *                                          identifier for each [[GameDatum]] object. The evaluators are
 *                                          a [[Seq]] of evaluators
 * @param logger A logger instance
 */
class CoordinateDescent(
    coordinates: Seq[(String, Coordinate[_ <: DataSet[_], _ <: Coordinate[_, _]])],
    trainingLossFunctionEvaluator: Evaluator,
    validatingDataAndEvaluatorsOption: Option[(RDD[(Long, GameDatum)], Seq[Evaluator])],
    logger: PhotonLogger) {

  /**
   * Run coordinate descent
   *
   * @param numIterations Number of iterations
   * @param seed Random seed (default: MathConst.RANDOM_SEED)
   * @return A trained GAME model
   */
  def run(numIterations: Int, seed: Long = MathConst.RANDOM_SEED): GAMEModel = {

    val initializedModelContainer = coordinates.map { case (coordinateId, coordinate) =>

      val initializedModel = coordinate.initializeModel(seed)
      initializedModel match {
        case rddLike: RDDLike =>
          rddLike
            .setName(s"Initialized model with coordinate id $coordinateId")
            .persistRDD(StorageLevel.INFREQUENT_REUSE_RDD_STORAGE_LEVEL)
        case _ =>
      }
      logger.debug(s"Summary of model (${initializedModel.getClass}}) initialized for coordinate with " +
          s"ID $coordinateId:\n${initializedModel.toSummaryString}\n")
      (coordinateId, initializedModel)
    }.toMap

    val initialGAMEModel = new GAMEModel(initializedModelContainer)
    run(numIterations, initialGAMEModel)
  }

  /**
   * Run coordinate descent. This function optimizes the model w.r.t. the objective
   * function. Optionally, it also evaluates the model on the validation data set using one or
   * more validation functions. In that case, the output is the model which yielded the
   * best evaluation on the validation data set w.r.t. the primary evaluation function.
   * Otherwise, it's simply the trained model.
   *
   * @param numIterations Number of iterations
   * @param gameModel The initial GAME model
   * @return The best GAME model (see above for exact meaning of "best")
   */
  def run(numIterations: Int, gameModel: GAMEModel): GAMEModel = {

    coordinates.foreach { case (coordinateId, _) =>
      require(gameModel.getModel(coordinateId).isDefined,
        s"Model with coordinateId $coordinateId is expected but not found from the initial GAME model!")
    }

    var updatedGAMEModel = gameModel

    // Initialize the training scores
    var updatedScoresContainer = coordinates.map { case (coordinateId, coordinate) =>
      val updatedScores = coordinate.score(updatedGAMEModel.getModel(coordinateId).get)
      updatedScores
        .setName(s"Initialized training scores with coordinateId $coordinateId")
        .persistRDD(StorageLevel.INFREQUENT_REUSE_RDD_STORAGE_LEVEL)

      (coordinateId, updatedScores)
    }.toMap

    // Initialize the validating scores
    var validatingScoresContainerOption = validatingDataAndEvaluatorsOption.map { case (validatingData, _) =>
      coordinates.map { case (coordinateId, _) =>
        val updatedModel = updatedGAMEModel.getModel(coordinateId).get
        val validatingScores = updatedModel.score(validatingData)
        validatingScores
          .setName(s"Initialized validating scores with coordinateId $coordinateId")
          .persistRDD(StorageLevel.INFREQUENT_REUSE_RDD_STORAGE_LEVEL)

        (coordinateId, validatingScores)
      }.toMap
    }

    // Initialize the regularization term value
    var regularizationTermValueContainer = coordinates.map { case (coordinateId, coordinate) =>
        val updatedModel = updatedGAMEModel.getModel(coordinateId).get

        (coordinateId, coordinate.computeRegularizationTermValue(updatedModel))
      }.toMap

    // This will track the "best" model according to the first evaluation function chosen by the user.
    // If the user did not specify any evaluation function, this var will be None.
    // NOTE: these two variables are updated by comparing *FULL* models, including random effects, i.e.
    // outside the loop on coordinates.
    // If we allowed the "best" model to be selected inside the loop over coordinates, the "best"
    // model could be one that doesn't contain some random effects.
    var bestModel: Option[GAMEModel] = None
    var bestEval: Option[Double] = None

    for (iteration <- 0 until numIterations) {

      val iterationTimer = Timer.start()
      logger.debug(s"Iteration $iteration of coordinate descent starts...\n")

      val currentEvaluation = coordinates.map { case (coordinateId, coordinate) =>

        val coordinateTimer = Timer.start()
        logger.debug(s"Start to update coordinate with ID $coordinateId (${coordinate.getClass})")

        // Update the model
        val modelUpdatingTimer = Timer.start()
        val oldModel = updatedGAMEModel.getModel(coordinateId).get
        val (updatedModel, optimizationTrackerOption) = if (updatedScoresContainer.keys.size > 1) {
          // If there are multiple coordinates,
          // collect scores for previously optimized coordinates into a partial score and update
          val partialScore = updatedScoresContainer.filterKeys(_ != coordinateId).values.reduce(_ + _)
          coordinate.updateModel(oldModel, partialScore)
        } else {
          // Otherwise, just update (very first coordinate)
          coordinate.updateModel(oldModel)
        }

        updatedModel match {
          case rddLike: RDDLike =>
            rddLike
              .setName(s"Updated model with coordinateId $coordinateId at iteration $iteration")
              .persistRDD(StorageLevel.INFREQUENT_REUSE_RDD_STORAGE_LEVEL)
              .materialize()
          case _ =>
        }
        // We need to split out the following 2 match expressions: [[FactoredRandomEffectModel]] matches both
        oldModel match {
          case broadcastLike: BroadcastLike => broadcastLike.unpersistBroadcast()
          case _ =>
        }
        oldModel match {
          case rddLike: RDDLike => rddLike.unpersistRDD()
          case _ =>
        }

        updatedGAMEModel = updatedGAMEModel.updateModel(coordinateId, updatedModel)

        // Summarize the current progress
        logger.info(s"Finished training the model in coordinate $coordinateId, " +
            s"time elapsed: ${modelUpdatingTimer.stop().durationSeconds} (s).")
        logger.debug(s"Summary of the learned model:\n${updatedModel.toSummaryString}")

        optimizationTrackerOption.foreach {
          optimizationTracker => logger.debug(s"OptimizationTracker:\n${optimizationTracker.toSummaryString}")
            optimizationTracker match {
              case rddLike: RDDLike => rddLike.unpersistRDD()
              case _ =>
            }
        }

        // Update the training score
        val updatedScores = coordinate.score(updatedModel)
        updatedScores
          .setName(s"Updated training scores with key $coordinateId at iteration $iteration")
          .persistRDD(StorageLevel.INFREQUENT_REUSE_RDD_STORAGE_LEVEL)
          .materialize()
        updatedScoresContainer(coordinateId).unpersistRDD()
        updatedScoresContainer = updatedScoresContainer.updated(coordinateId, updatedScores)

        // Update the regularization term value
        val updatedRegularizationTermValue = coordinate.computeRegularizationTermValue(updatedModel)
        regularizationTermValueContainer =
          regularizationTermValueContainer.updated(coordinateId, updatedRegularizationTermValue)
        // Compute the training objective function value
        val fullScore = updatedScoresContainer.values.reduce(_ + _)
        val lossFunctionValue = trainingLossFunctionEvaluator.evaluate(fullScore.scores)
        val regularizationTermValue = regularizationTermValueContainer.values.sum
        val objectiveFunctionValue = ObjectiveFunctionValue(lossFunctionValue, regularizationTermValue)
        logger.info(s"Training objective function value after updating coordinate with id $coordinateId at " +
            s"iteration $iteration is:\n$objectiveFunctionValue")

        // Update the validating score and evaluate the updated model on the validating data
        val currentEvaluation =
        validatingDataAndEvaluatorsOption.map { case (validatingData, evaluators) =>

          val validationTimer = Timer.start()
          val validatingScoresContainer = validatingScoresContainerOption.get
          validatingScoresContainer.updated(coordinateId,
            updatedModel
              .score(validatingData)
              .setName(s"Updated validating scores with coordinateId $coordinateId")
              .persistRDD(StorageLevel.INFREQUENT_REUSE_RDD_STORAGE_LEVEL)
              .materialize()
          )
          validatingScoresContainer(coordinateId).unpersistRDD()
          validatingScoresContainerOption = Some(validatingScoresContainer)
          val fullScore = validatingScoresContainer.values.reduce(_ + _)

          val (firstEvaluator, currentEvaluation) = evaluators.map { evaluator =>
            val evaluationTimer = Timer.start()
            val evaluation = evaluator.evaluate(fullScore.scores)
            logger.debug(s"Finished score evaluation, time elapsed: ${evaluationTimer.stop().durationSeconds} (s).")
            logger.info(s"Evaluation metric computed with ${evaluator.getEvaluatorName} after updating " +
              s"coordinateId $coordinateId at iteration $iteration is $evaluation")

            (evaluator, evaluation)
          }.head // we use only the first evaluator to select the "best" model

          logger.debug(s"Finished validating model, time elapsed: ${validationTimer.stop().durationSeconds} (s).")

          (firstEvaluator, currentEvaluation)
        } // validatingDataAndEvaluatorsOption.map

        logger.info(s"Updating coordinate $coordinateId finished, time elapsed: " +
            s"${coordinateTimer.stop().durationSeconds} (s)\n")

        currentEvaluation // the first evaluator of the last coordinate evaluates the full model
      }.last // end each coordinate

      currentEvaluation match {
        case Some((firstEvaluator: Evaluator, currentEval: Double)) =>
          if (bestEval.isEmpty || firstEvaluator.betterThan(currentEval, bestEval.get)) {
            bestEval = Some(currentEval)
            bestModel = Some(updatedGAMEModel)
            logger.debug(s"Found better GAME model, with evaluation: $currentEval")
          }

        case _ =>
          logger.debug("No evaulator specified to select best model")
        }

      logger.info(s"Iteration $iteration of coordinate descent finished, time elapsed: " +
          s"${iterationTimer.stop().durationSeconds} (s)\n\n")

    } // end iterations

    bestModel.getOrElse(updatedGAMEModel)
  } // end run
}
