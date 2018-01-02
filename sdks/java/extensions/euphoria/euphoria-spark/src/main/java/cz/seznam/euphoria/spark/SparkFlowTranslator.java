/*
 * Copyright 2016-2018 Seznam.cz, a.s.
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
package cz.seznam.euphoria.spark;

import cz.seznam.euphoria.core.client.dataset.windowing.GlobalWindowing;
import cz.seznam.euphoria.core.client.dataset.windowing.MergingWindowing;
import cz.seznam.euphoria.core.client.flow.Flow;
import cz.seznam.euphoria.core.client.functional.UnaryPredicate;
import cz.seznam.euphoria.core.client.io.DataSink;
import cz.seznam.euphoria.core.client.operator.FlatMap;
import cz.seznam.euphoria.core.client.operator.Join;
import cz.seznam.euphoria.core.client.operator.Operator;
import cz.seznam.euphoria.core.client.operator.ReduceByKey;
import cz.seznam.euphoria.core.client.operator.ReduceStateByKey;
import cz.seznam.euphoria.core.client.operator.Union;
import cz.seznam.euphoria.core.executor.FlowUnfolder;
import cz.seznam.euphoria.core.executor.graph.DAG;
import cz.seznam.euphoria.core.executor.graph.Node;
import cz.seznam.euphoria.core.util.Settings;
import cz.seznam.euphoria.hadoop.output.DataSinkOutputFormat;
import cz.seznam.euphoria.spark.accumulators.SparkAccumulatorFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import scala.Tuple2;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Translates given {@link Flow} into Spark execution environment
 */
class SparkFlowTranslator {

  /* mapping of Euphoria operators to corresponding Flink transformations */
  private final Map<Class, List<Translation>> translations = new IdentityHashMap<>();

  private final JavaSparkContext sparkEnv;
  private final Settings settings;
  private final SparkAccumulatorFactory accumulatorFactory;

  SparkFlowTranslator(JavaSparkContext sparkEnv,
                      Settings flowSettings,
                      SparkAccumulatorFactory accumulatorFactory) {
    this.sparkEnv = Objects.requireNonNull(sparkEnv);
    this.settings = Objects.requireNonNull(flowSettings);
    this.accumulatorFactory = Objects.requireNonNull(accumulatorFactory);

    // ~ basic operators
    Translation.add(translations, FlowUnfolder.InputOperator.class, new InputTranslator());
    Translation.add(translations, FlatMap.class, new FlatMapTranslator());
    Translation.add(translations, ReduceStateByKey.class, new ReduceStateByKeyTranslator(settings));
    Translation.add(translations, Union.class, new UnionTranslator());

    // ~ derived operators
    Translation.add(translations, ReduceByKey.class, new ReduceByKeyTranslator(),
        ReduceByKeyTranslator::wantTranslate);

    // ~ batch broadcast join for a very small left side
    Translation.add(translations, Join.class, new BroadcastHashJoinTranslator(),
        BroadcastHashJoinTranslator::wantTranslate);
  }

  @SuppressWarnings("unchecked")
  List<DataSink<?>> translateInto(Flow flow) {
    // ~ transform flow to direct acyclic graph of supported operators
    final DAG<Operator<?, ?>> dag = flowToDag(flow);

    final SparkExecutorContext executorContext =
        new SparkExecutorContext(sparkEnv, dag, accumulatorFactory, settings);

    // ~ translate each operator to proper Spark transformation
    dag.traverse().map(Node::get).forEach(op -> {
      final List<Translation> txs = translations.get(op.getClass());
      if (txs.isEmpty()) {
        throw new UnsupportedOperationException(
            "Operator " + op.getClass().getSimpleName() + " not supported");
      }
      // ~ verify the flowToDag translation
      Translation firstMatch = null;
      for (Translation tx : txs) {
        if (tx.accept == null || Boolean.TRUE.equals(tx.accept.apply(op))) {
          firstMatch = tx;
          break;
        }
      }
      if (firstMatch != null) {
        final JavaRDD<?> out = firstMatch.translator.translate(op, executorContext);
        // ~ save output of current operator to context
        executorContext.setOutput(op, out);
      } else {
        throw new IllegalStateException("No matching translation.");
      }
    });

    // process all sinks in the DAG (leaf nodes)
    final List<DataSink<?>> sinks = new ArrayList<>();
    dag.getLeafs()
        .stream()
        .map(Node::get)
        .filter(op -> op.output().getOutputSink() != null)
        .forEach(op -> {

          final DataSink<?> sink = op.output().getOutputSink();
          sinks.add(sink);
          JavaRDD<SparkElement> sparkOutput =
              Objects.requireNonNull((JavaRDD) executorContext.getOutput(op));

          // unwrap data from WindowedElement
          JavaPairRDD<NullWritable, Object> unwrapped =
              sparkOutput.mapToPair(el -> new Tuple2<>(NullWritable.get(), el.getElement()));


          try {
            Configuration conf =
                DataSinkOutputFormat.configure(new Configuration(), sink);

            conf.set(JobContext.OUTPUT_FORMAT_CLASS_ATTR,
                DataSinkOutputFormat.class.getName());

            // FIXME blocking op
            unwrapped.saveAsNewAPIHadoopDataset(conf);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });

    return sinks;
  }

  /**
   * A functor to accept operators for translation if the  operator's
   * type equals a specified, fixed type. An optional custom "accept"
   * function can be provided to further tweak the decision whether
   * a particular operator instance is to be accepted for translation
   * or not.
   *
   * @param <O> the fixed operator type accepted
   */
  public static final class TranslateAcceptor<O>
      implements UnaryPredicate<Operator<?, ?>> {

    final Class<O> type;
    @Nullable
    final UnaryPredicate<O> accept;

    public TranslateAcceptor(Class<O> type) {
      this(type, null);
    }

    public TranslateAcceptor(Class<O> type, @Nullable UnaryPredicate<O> accept) {
      this.type = Objects.requireNonNull(type);
      this.accept = accept;
    }

    @Override
    public Boolean apply(Operator<?, ?> operator) {
      return type == operator.getClass()
          && (accept == null || accept.apply(type.cast(operator)));
    }
  }

  /**
   * Converts a {@link Flow} into a {@link DAG} of Flink specific {@link Operator}s.
   * <p>
   * Invokes {@link #getAcceptors()} to determine which user provided
   * operators to accept for direct translation, i.e. which to leave in
   * the resulting DAG without expanding them to their {@link Operator#getBasicOps()}.
   *
   * @param flow the user defined flow to translate
   *
   * @return a DAG representing the specified flow; never {@code null}
   *
   * @throws IllegalStateException if validation of the specified flow failed
   *          for some reason
   */
  private DAG<Operator<?, ?>> flowToDag(Flow flow) {
    // ~ get acceptors for translation
    final Map<Class, Collection<TranslateAcceptor>> acceptors =
        buildAcceptorsIndex(getAcceptors());
    // ~ now, unfold the flow based on the specified acceptors
    return FlowUnfolder.unfold(flow, operator -> {
      // accept the operator if any of the specified acceptors says so
      final Collection<TranslateAcceptor> accs = acceptors.get(operator.getClass());
      if (accs != null && !accs.isEmpty()) {
        for (TranslateAcceptor<?> acc : accs) {
          if (acc.apply(operator)) {
            return true;
          }
        }
      }
      return false;
    });
  }

  /**
   * Helper method to build an index over the given acceptors by
   * {@link TranslateAcceptor#type}.
   */
  private Map<Class, Collection<TranslateAcceptor>> buildAcceptorsIndex(
      Collection<TranslateAcceptor> acceptors) {
    final IdentityHashMap<Class, Collection<TranslateAcceptor>> idx =
        new IdentityHashMap<>(acceptors.size());
    for (TranslateAcceptor<?> acc : acceptors) {
      idx.computeIfAbsent(acc.type, k -> new ArrayList<>()).add(acc);
    }
    return idx;
  }

  @SuppressWarnings("unchecked")
  private Collection<TranslateAcceptor> getAcceptors() {
    return translations.entrySet().stream()
        .flatMap((entry) -> entry.getValue()
            .stream()
            .map(translator -> new TranslateAcceptor(entry.getKey(), translator.accept)))
        .collect(Collectors.toList());
  }

  private static class Translation<O extends Operator<?, ?>> {

    final SparkOperatorTranslator<O> translator;
    final UnaryPredicate<O> accept;

    private Translation(SparkOperatorTranslator<O> translator, UnaryPredicate<O> accept) {
      this.translator = Objects.requireNonNull(translator);
      this.accept = accept;
    }

    static <O extends Operator<?, ?>> void add(Map<Class, List<Translation>> idx,
                                               Class<O> type,
                                               SparkOperatorTranslator<O> translator) {
      add(idx, type, translator, null);
    }

    static <O extends Operator<?, ?>> void add(Map<Class, List<Translation>> idx,
                                               Class<O> type,
                                               SparkOperatorTranslator<O> translator,
                                               UnaryPredicate<O> accept) {
      if (!idx.containsKey(type)) {
        idx.put(type, new ArrayList<>());
      }
      idx.get(type).add(new Translation<>(translator, accept));
    }
  }

}
