package com.yammer.metrics.servlet;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.*;
import com.yammer.metrics.reporting.MetricDispatcher;
import com.yammer.metrics.stats.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * An HTTP servlet which outputs the metrics in a {@link MetricRegistry} (and optionally the data
 * provided by {@link VirtualMachineMetrics}) in a JSON object. Only responds to {@code GET}
 * requests.
 * <p/>
 * If the servlet context has an attribute named
 * {@code com.yammer.metrics.servlet.MetricsServlet.registry} which is a
 * {@link MetricRegistry} instance, {@link MetricsServlet} will use it instead of {@link Metrics}.
 * <p/>
 * {@link MetricsServlet} also takes an initialization parameter, {@code show-jvm-metrics}, which
 * should be a boolean value (e.g., {@code "true"} or {@code "false"}). It determines whether or not
 * JVM-level metrics will be included in the JSON output.
 * <p/>
 * {@code GET} requests to {@link MetricsServlet} can make use of the following query-string
 * parameters:
 * <dl>
 *     <dt><code>/metrics?class=com.example.service</code></dt>
 *     <dd>
 *         <code>class</code> is a string used to filter the metrics in the JSON by metric name. In
 *         the given example, only metrics for classes whose canonical name starts with
 *         <code>com.example.service</code> would be shown. You can also use <code>jvm</code> for
 *         just the JVM-level metrics.
 *     </dd>
 *
 *     <dt><code>/metrics?pretty=true</code></dt>
 *     <dd>
 *         <code>pretty</code> determines whether or not the JSON which is returned is printed with
 *         indented whitespace or not. If you're looking at the JSON in the browser, use this.
 *     </dd>
 *
 *     <dt><code>/metrics?full-samples=true</code></dt>
 *     <dd>
 *         <code>full-samples</code> determines whether or not the JSON which is returned will
 *         include the full content of histograms' and timers' reservoir samples. If you're
 *         aggregating across hosts, you may want to do this to allow for more accurate quantile
 *         calculations.
 *     </dd>
 * </dl>
 */
public class MetricsServlet extends HttpServlet implements MetricProcessor<MetricsServlet.Context> {

    /**
     * The attribute name of the {@link MetricRegistry} instance in the servlet context.
     */
    public static final String REGISTRY_ATTRIBUTE = MetricsServlet.class.getName() + ".registry";

    /**
     * The attribute name of the {@link JsonFactory} instance in the servlet context.
     */
    public static final String JSON_FACTORY_ATTRIBUTE = JsonFactory.class.getCanonicalName();

    /**
     * The initialization parameter name which determines whether or not JVM_level metrics will be
     * included in the JSON output.
     */
    public static final String SHOW_JVM_METRICS = "show-jvm-metrics";

    /**
     * The duration unit to use for times.
     */
    public static final String DURATION_UNIT = "duration-unit";

    static final class Context {
        final boolean showFullSamples;
        final JsonGenerator json;

        Context(JsonGenerator json, boolean showFullSamples) {
            this.json = json;
            this.showFullSamples = showFullSamples;
        }
    }

    private static final JsonFactory DEFAULT_JSON_FACTORY = new JsonFactory(new ObjectMapper());
    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsServlet.class);
    private static final String CONTENT_TYPE = "application/json";

    private final Clock clock;
    private final VirtualMachineMetrics vm;
    private TimeUnit durationUnit;
    private MetricRegistry registry;
    private JsonFactory factory;
    private boolean showJvmMetrics;

    /**
     * Creates a new {@link MetricsServlet}.
     */
    public MetricsServlet() {
        this(Clock.defaultClock(),
             VirtualMachineMetrics.getInstance(),
             Metrics.defaultRegistry(),
             DEFAULT_JSON_FACTORY,
             true,
             TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new {@link MetricsServlet}.
     *
     * @param showJvmMetrics    whether or not JVM-level metrics will be included in the output
     */
    public MetricsServlet(boolean showJvmMetrics, TimeUnit durationUnit) {
        this(Clock.defaultClock(),
             VirtualMachineMetrics.getInstance(),
             Metrics.defaultRegistry(),
             DEFAULT_JSON_FACTORY,
             showJvmMetrics,
             durationUnit);
    }

    /**
     * Creates a new {@link MetricsServlet}.
     *
     * @param clock             the clock used for the current time
     * @param vm                a {@link VirtualMachineMetrics} instance
     * @param registry          a {@link MetricRegistry}
     * @param factory           a {@link JsonFactory}
     * @param showJvmMetrics    whether or not JVM-level metrics will be included in the output
     */
    public MetricsServlet(Clock clock,
                          VirtualMachineMetrics vm,
                          MetricRegistry registry,
                          JsonFactory factory,
                          boolean showJvmMetrics,
                          TimeUnit durationUnit) {
        this.clock = clock;
        this.vm = vm;
        this.registry = registry;
        this.factory = factory;
        this.showJvmMetrics = showJvmMetrics;
        this.durationUnit = durationUnit;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        final Object factory = config.getServletContext()
                                     .getAttribute(JSON_FACTORY_ATTRIBUTE);
        if (factory instanceof JsonFactory) {
            this.factory = (JsonFactory) factory;
        }

        final Object o = config.getServletContext().getAttribute(REGISTRY_ATTRIBUTE);
        if (o instanceof MetricRegistry) {
            this.registry = (MetricRegistry) o;
        }

        final String showJvmMetricsParam = config.getInitParameter(SHOW_JVM_METRICS);
        if (showJvmMetricsParam != null) {
            this.showJvmMetrics = Boolean.parseBoolean(showJvmMetricsParam);
        }

        final String unit = config.getInitParameter(DURATION_UNIT);
        if (unit != null) {
            this.durationUnit = TimeUnit.valueOf(unit.toUpperCase());
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        final String classPrefix = req.getParameter("class");
        final boolean pretty = Boolean.parseBoolean(req.getParameter("pretty"));
        final boolean showFullSamples = Boolean.parseBoolean(req.getParameter("full-samples"));

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(CONTENT_TYPE);
        final OutputStream output = resp.getOutputStream();
        final JsonGenerator json = factory.createJsonGenerator(output, JsonEncoding.UTF8);
        if (pretty) {
            json.useDefaultPrettyPrinter();
        }
        json.writeStartObject();
        {
            if (showJvmMetrics && ("jvm".equals(classPrefix) || classPrefix == null)) {
                writeVmMetrics(json);
            }

            writeRegularMetrics(json, classPrefix, showFullSamples);
        }
        json.writeEndObject();
        json.close();
    }

    private void writeVmMetrics(JsonGenerator json) throws IOException {
        json.writeFieldName("jvm");
        json.writeStartObject();
        {
            json.writeFieldName("vm");
            json.writeStartObject();
            {
                json.writeStringField("name", vm.getName());
                json.writeStringField("version", vm.getVersion());
            }
            json.writeEndObject();

            json.writeFieldName("memory");
            json.writeStartObject();
            {
                json.writeNumberField("totalInit", vm.getTotalInit());
                json.writeNumberField("totalUsed", vm.getTotalUsed());
                json.writeNumberField("totalMax", vm.getTotalMax());
                json.writeNumberField("totalCommitted", vm.getTotalCommitted());

                json.writeNumberField("heapInit", vm.getHeapInit());
                json.writeNumberField("heapUsed", vm.getHeapUsed());
                json.writeNumberField("heapMax", vm.getHeapMax());
                json.writeNumberField("heapCommitted", vm.getHeapCommitted());

                json.writeNumberField("heap_usage", vm.getHeapUsage());
                json.writeNumberField("non_heap_usage", vm.getNonHeapUsage());
                json.writeFieldName("memory_pool_usages");
                json.writeStartObject();
                {
                    for (Map.Entry<String, Double> pool : vm.getMemoryPoolUsage().entrySet()) {
                        json.writeNumberField(pool.getKey(), pool.getValue());
                    }
                }
                json.writeEndObject();
            }
            json.writeEndObject();

            final Map<String, VirtualMachineMetrics.BufferPoolStats> bufferPoolStats = vm.getBufferPoolStats();
            if (!bufferPoolStats.isEmpty()) {
                json.writeFieldName("buffers");
                json.writeStartObject();
                {
                    json.writeFieldName("direct");
                    json.writeStartObject();
                    {
                        json.writeNumberField("count", bufferPoolStats.get("direct").getCount());
                        json.writeNumberField("memoryUsed", bufferPoolStats.get("direct").getMemoryUsed());
                        json.writeNumberField("totalCapacity", bufferPoolStats.get("direct").getTotalCapacity());
                    }
                    json.writeEndObject();

                    json.writeFieldName("mapped");
                    json.writeStartObject();
                    {
                        json.writeNumberField("count", bufferPoolStats.get("mapped").getCount());
                        json.writeNumberField("memoryUsed", bufferPoolStats.get("mapped").getMemoryUsed());
                        json.writeNumberField("totalCapacity", bufferPoolStats.get("mapped").getTotalCapacity());
                    }
                    json.writeEndObject();
                }
                json.writeEndObject();
            }


            json.writeNumberField("daemon_thread_count", vm.getDaemonThreadCount());
            json.writeNumberField("thread_count", vm.getThreadCount());
            json.writeNumberField("current_time", clock.getTime());
            json.writeNumberField("uptime", vm.getUptime());
            json.writeNumberField("fd_usage", vm.getFileDescriptorUsage());

            json.writeFieldName("thread-states");
            json.writeStartObject();
            {
                for (Map.Entry<Thread.State, Double> entry : vm.getThreadStatePercentages()
                                                               .entrySet()) {
                    json.writeNumberField(entry.getKey().toString().toLowerCase(),
                                          entry.getValue());
                }
            }
            json.writeEndObject();

            json.writeFieldName("garbage-collectors");
            json.writeStartObject();
            {
                for (Map.Entry<String, VirtualMachineMetrics.GarbageCollectorStats> entry : vm.getGarbageCollectors()
                                                                                              .entrySet()) {
                    json.writeFieldName(entry.getKey());
                    json.writeStartObject();
                    {
                        final VirtualMachineMetrics.GarbageCollectorStats gc = entry.getValue();
                        json.writeNumberField("runs", gc.getRuns());
                        json.writeNumberField("time", gc.getTime(TimeUnit.MILLISECONDS));
                    }
                    json.writeEndObject();
                }
            }
            json.writeEndObject();
        }
        json.writeEndObject();
    }

    public void writeRegularMetrics(JsonGenerator json, String classPrefix, boolean showFullSamples) throws IOException {
        final MetricDispatcher dispatcher = new MetricDispatcher();
        for (Map.Entry<String, Metric> entry : registry) {
            json.writeFieldName(entry.getKey());
            try {
                dispatcher.dispatch(entry.getValue(),
                                    entry.getKey(),
                                    this,
                                    new Context(json, showFullSamples));
            } catch (Exception e) {
                LOGGER.warn("Error writing out " + entry.getKey(), e);
            }
        }
    }

    @Override
    public void processHistogram(String name, Histogram histogram, Context context) throws Exception {
        final JsonGenerator json = context.json;
        json.writeStartObject();
        {
            json.writeStringField("type", "histogram");
            json.writeNumberField("count", histogram.getCount());
            json.writeNumberField("min", histogram.getMin());
            json.writeNumberField("max", histogram.getMax());
            json.writeNumberField("mean", histogram.getMean());
            json.writeNumberField("std_dev", histogram.getStdDev());
            final Snapshot snapshot = histogram.getSnapshot();
            json.writeNumberField("median", snapshot.getMedian());
            json.writeNumberField("p75", snapshot.get75thPercentile());
            json.writeNumberField("p95", snapshot.get95thPercentile());
            json.writeNumberField("p98", snapshot.get98thPercentile());
            json.writeNumberField("p99", snapshot.get99thPercentile());
            json.writeNumberField("p999", snapshot.get999thPercentile());

            if (context.showFullSamples) {
                json.writeObjectField("values", histogram.getSnapshot().getValues());
            }
        }
        json.writeEndObject();
    }

    @Override
    public void processCounter(String name, Counter counter, Context context) throws Exception {
        final JsonGenerator json = context.json;
        json.writeStartObject();
        {
            json.writeStringField("type", "counter");
            json.writeNumberField("count", counter.getCount());
        }
        json.writeEndObject();
    }

    @Override
    public void processGauge(String name, Gauge<?> gauge, Context context) throws Exception {
        final JsonGenerator json = context.json;
        json.writeStartObject();
        {
            json.writeStringField("type", "gauge");
            json.writeObjectField("value", evaluateGauge(gauge));
        }
        json.writeEndObject();
    }

    @Override
    public void processMeter(String name, Metered meter, Context context) throws Exception {
        final JsonGenerator json = context.json;
        json.writeStartObject();
        {
            json.writeStringField("type", "meter");
            writeMeteredFields(meter, json);
        }
        json.writeEndObject();
    }

    @Override
    public void processTimer(String name, Timer timer, Context context) throws Exception {
        final JsonGenerator json = context.json;
        json.writeStartObject();
        {
            json.writeStringField("type", "timer");
            json.writeFieldName("duration");
            json.writeStartObject();
            {
                json.writeNumberField("min", convertFromNS(timer.getMin()));
                json.writeNumberField("max", convertFromNS(timer.getMax()));
                json.writeNumberField("mean", convertFromNS(timer.getMean()));
                json.writeNumberField("std_dev", convertFromNS(timer.getStdDev()));
                final Snapshot snapshot = timer.getSnapshot();
                json.writeNumberField("median", convertFromNS(snapshot.getMedian()));
                json.writeNumberField("p75", convertFromNS(snapshot.get75thPercentile()));
                json.writeNumberField("p95", convertFromNS(snapshot.get95thPercentile()));
                json.writeNumberField("p98", convertFromNS(snapshot.get98thPercentile()));
                json.writeNumberField("p99", convertFromNS(snapshot.get99thPercentile()));
                json.writeNumberField("p999", convertFromNS(snapshot.get999thPercentile()));
                if (context.showFullSamples) {
                    final long[] values = timer.getSnapshot().getValues();
                    final double[] converted = new double[values.length];
                    for (int i = 0; i < converted.length; i++) {
                        converted[i] = convertFromNS(values[i]);
                    }
                    json.writeObjectField("values", converted);
                }
            }
            json.writeEndObject();

            json.writeFieldName("rate");
            json.writeStartObject();
            {
                writeMeteredFields(timer, json);
            }
            json.writeEndObject();
        }
        json.writeEndObject();
    }

    private static Object evaluateGauge(Gauge<?> gauge) {
        try {
            return gauge.getValue();
        } catch (RuntimeException e) {
            LOGGER.warn("Error evaluating gauge", e);
            return "error reading gauge: " + e.getMessage();
        }
    }

    private static void writeMeteredFields(Metered metered, JsonGenerator json) throws IOException {
        json.writeNumberField("count", metered.getCount());
        json.writeNumberField("mean", metered.getMeanRate());
        json.writeNumberField("m1", metered.getOneMinuteRate());
        json.writeNumberField("m5", metered.getFiveMinuteRate());
        json.writeNumberField("m15", metered.getFifteenMinuteRate());
    }

    private double convertFromNS(long ns) {
        return ns / (double) durationUnit.toNanos(1);
    }

    private double convertFromNS(double ns) {
        return ns / (double) durationUnit.toNanos(1);
    }
}