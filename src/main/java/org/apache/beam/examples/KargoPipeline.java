/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.examples;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;

import org.apache.beam.examples.common.ExampleUtils;
import org.apache.beam.examples.common.WriteOneFilePerWindow;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.Method;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.Validation.Required;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;

/**
 * An example that counts words in Shakespeare and includes Beam best practices.
 *
 * <p>This class, {@link WordCount}, is the second in a series of four successively more detailed
 * 'word count' examples. You may first want to take a look at {@link MinimalWordCount}. After
 * you've looked at this example, then see the {@link DebuggingWordCount} pipeline, for introduction
 * of additional concepts.
 *
 * <p>For a detailed walkthrough of this example, see <a
 * href="https://beam.apache.org/get-started/wordcount-example/">
 * https://beam.apache.org/get-started/wordcount-example/ </a>
 *
 * <p>Basic concepts, also in the MinimalWordCount example: Reading text files; counting a
 * PCollection; writing to text files
 *
 * <p>New Concepts:
 *
 * <pre>
 *   1. Executing a Pipeline both locally and using the selected runner
 *   2. Using ParDo with static DoFns defined out-of-line
 *   3. Building a composite transform
 *   4. Defining your own pipeline options
 * </pre>
 *
 * <p>Concept #1: you can execute this pipeline either locally or using by selecting another runner.
 * These are now command-line options and not hard-coded as they were in the MinimalWordCount
 * example.
 *
 * <p>To change the runner, specify:
 *
 * <pre>{@code
 * --runner=YOUR_SELECTED_RUNNER
 * }</pre>
 *
 * <p>To execute this pipeline, specify a local output file (if using the {@code DirectRunner}) or
 * output prefix on a supported distributed file system.
 *
 * <pre>{@code
 * --output=[YOUR_LOCAL_FILE | YOUR_OUTPUT_PREFIX]
 * }</pre>
 *
 * <p>The input file defaults to a public data set containing the text of of King Lear, by William
 * Shakespeare. You can override it and choose your own input with {@code --inputFile}.
 */
public class KargoPipeline {

  static final String TOKENIZER_PATTERN = "[^\\p{L}]+";

  /**
   * Concept #2: You can make your pipeline assembly code less verbose by defining your DoFns
   * statically out-of-line. This DoFn tokenizes lines of text into individual words; we pass it to
   * a ParDo in the pipeline.
   */
  static class ExtractWordsFn extends DoFn<String, String> {
    private final Counter emptyLines = Metrics.counter(ExtractWordsFn.class, "emptyLines");
    private final Distribution lineLenDist =
        Metrics.distribution(ExtractWordsFn.class, "lineLenDistro");

    @ProcessElement
    public void processElement(@Element String element, OutputReceiver<String> receiver) {
      lineLenDist.update(element.length());
      if (element.trim().isEmpty()) {
        emptyLines.inc();
      }

      // Split the line into words.
      String[] words = element.split(ExampleUtils.TOKENIZER_PATTERN, -1);

      // Output each word encountered into the output PCollection.
      for (String word : words) {
        if (!word.isEmpty()) {
          receiver.output(word);
        }
      }
    }
  }

  /** A SimpleFunction that converts a Word and Count into a printable string. */
  public static class FormatAsTextFn extends SimpleFunction<KV<String, Long>, String> {
    @Override
    public String apply(KV<String, Long> input) {
      return input.getKey() + ": " + input.getValue();
    }
  }

  /**
   * A PTransform that converts a PCollection containing lines of text into a PCollection of
   * formatted word counts.
   *
   * <p>Concept #3: This is a custom composite transform that bundles two transforms (ParDo and
   * Count) as a reusable PTransform subclass. Using composite transforms allows for easy reuse,
   * modular testing, and an improved monitoring experience.
   */
  public static class CountWords
      extends PTransform<PCollection<String>, PCollection<KV<String, Long>>> {
    @Override
    public PCollection<KV<String, Long>> expand(PCollection<String> lines) {

      // Convert lines of text into individual words.
      PCollection<String> words = lines.apply(ParDo.of(new ExtractWordsFn()));

      // Count the number of times each word occurs.
      PCollection<KV<String, Long>> wordCounts = words.apply(Count.perElement());

      return wordCounts;
    }
  }

  public static class JsonToTableRowFn extends DoFn<String, TableRow> {


    public static TableRow convertJsonToTableRow(String json) {
      TableRow row;
      // Parse the JSON into a {@link TableRow} object.
      try (InputStream inputStream =
          new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
        //row = TableRowJsonCoder.of().decode(inputStream, Context.OUTER);
        row = new TableRow().set("json_data", json);
      } catch (IOException e) {
        throw new RuntimeException("Failed to serialize json to table row: " + json, e);
      }

      return row;
    }

    @ProcessElement
    public void processElement(ProcessContext context) {
      String json = context.element();
      context.output(convertJsonToTableRow(json));
    }


  }

  /**
   * Options supported by {@link WordCount}.
   *
   * <p>Concept #4: Defining your own configuration options. Here, you can add your own arguments to
   * be processed by the command-line parser, and specify default values for them. You can then
   * access the options values in your pipeline code.
   *
   * <p>Inherits standard configuration options.
   */
  public interface GCSToBigQueryOptions extends PipelineOptions {

    /**
     * By default, this example reads from a public dataset containing the text of King Lear. Set
     * this option to choose a different input file or glob.
     */
    @Description("Path of the file to read from")
    @Default.String("gs://gautam1/data/crypto*")
    String getInputFile();

    void setInputFile(String value);

    /** Set this required option to specify where to write the output. */
    @Description("Path of the file to write to")
    @Required
    String getOutput();

    void setOutput(String value);
  }

  static void runGCSToBigQueryPipeline(GCSToBigQueryOptions options) {
    Pipeline p = Pipeline.create(options);

    String project = "sada-gautam";
    String dataset = "dataset1";
    String table = "crypto_ex_12";

    TableSchema schema = new TableSchema()
                            .setFields(
                                Arrays.asList(
                                new TableFieldSchema().setName("json_data").setType("STRING")
                            ));

    p.begin().apply(

        "ReadFromGCS",
        TextIO.read().from("gs://gautam1/data2/file_list.txt")
            //.from(options.getInputFile())
            //.withCompression(Compression.GZIP)
            )
        .apply(TextIO.readAll())
        //.apply(Window.<String>into(FixedWindows.of(Duration.standardSeconds(30))))
        .apply(ParDo.of(new JsonToTableRowFn()))
        .apply("StreamingToBigQuery", 
          BigQueryIO.writeTableRows()
            //.withMethod(Method.STREAMING_INSERTS)
            .to(String.format("%s:%s.%s", project, dataset, table))
            .withSchema(schema)
            // For CreateDisposition:
            // - CREATE_IF_NEEDED (default): creates the table if it doesn't exist, a schema is
            // required
            // - CREATE_NEVER: raises an error if the table doesn't exist, a schema is not needed
            .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
            // For WriteDisposition:
            // - WRITE_EMPTY (default): raises an error if the table is not empty
            // - WRITE_APPEND: appends new rows to existing rows
            // - WRITE_TRUNCATE: deletes the existing rows before writing
            .withWriteDisposition(WriteDisposition.WRITE_APPEND)
        )
      
        ;
        /*.apply("Writing to GCS", TextIO.write()
                                  .to("gs://gautam1/data1/kafka-")
                                  .withWindowedWrites()
              );*/

    p.run().waitUntilFinish();
  }

  static void runPubsubToGCS(GCSToBigQueryOptions options) {
    Pipeline p = Pipeline.create(options);

    p.begin().apply(

        "ReadFromPubsub",
        PubsubIO.readStrings().fromSubscription("projects/sada-gautam/subscriptions/my-sub-1")
            //.from(options.getInputFile())
            //.withCompression(Compression.GZIP)
            )
            .apply(Window.into(FixedWindows.of(Duration.standardMinutes(30))))
            // 3) Write one file to GCS for every window of messages.
            .apply("Write Files to GCS", new WriteOneFilePerWindow("gs://gautam1/data2/file_list-", 1)
            );

    p.run().waitUntilFinish();

  }

  public static void main(String[] args) {
    GCSToBigQueryOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(GCSToBigQueryOptions.class);

    runPubsubToGCS(options);

    //runGCSToBigQueryPipeline(options);
  }
}
