/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.micrometer.core.instrument.cumulative.*;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.internal.DefaultGauge;
import io.micrometer.core.instrument.internal.DefaultLongTaskTimer;
import io.micrometer.core.instrument.internal.DefaultMeter;
import io.micrometer.core.instrument.util.TimeUtils;

import org.apache.maven.plugin.logging.Log;
import org.openrewrite.internal.lang.NonNullApi;
import org.openrewrite.internal.lang.Nullable;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.stream.StreamSupport;

import static io.micrometer.core.instrument.util.DoubleFormat.decimalOrNan;
import static java.util.stream.Collectors.joining;

@NonNullApi
public class MavenLoggingMeterRegistry extends MeterRegistry {
    private final Log log;

    public MavenLoggingMeterRegistry(Log log) {
        super(Clock.SYSTEM);
        this.log = log;
    }

    @Override
    public void close() {
        getMeters().stream()
                .sorted((m1, m2) -> {
                    int typeComp = m1.getId().getType().compareTo(m2.getId().getType());
                    if (typeComp == 0) {
                        return m1.getId().getName().compareTo(m2.getId().getName());
                    }
                    return typeComp;
                })
                .forEach(m -> {
                    Printer print = new Printer(m);
                    m.use(
                            gauge -> log.info(print.id() + " value=" + print.value(gauge.value())),
                            counter -> {
                                double count = counter.count();
                                log.info(print.id() + " count=" + print.count(count));
                            },
                            timer -> {
                                HistogramSnapshot snapshot = timer.takeSnapshot();
                                long count = snapshot.count();
                                log.info(print.id() + " count=" + print.unitlessCount(count) +
                                        " mean=" + print.time(snapshot.mean(getBaseTimeUnit())) +
                                        " max=" + print.time(snapshot.max(getBaseTimeUnit())));
                            },
                            summary -> {
                                HistogramSnapshot snapshot = summary.takeSnapshot();
                                long count = snapshot.count();
                                log.info(print.id() + " count=" + print.unitlessCount(count) +
                                        " mean=" + print.value(snapshot.mean()) +
                                        " max=" + print.value(snapshot.max()));
                            },
                            longTaskTimer -> {
                                int activeTasks = longTaskTimer.activeTasks();
                                log.info(print.id() +
                                        " active=" + print.value(activeTasks) +
                                        " duration=" + print.time(longTaskTimer.duration(getBaseTimeUnit())));
                            },
                            timeGauge -> {
                                double value = timeGauge.value(getBaseTimeUnit());
                                log.info(print.id() + " value=" + print.time(value));
                            },
                            counter -> {
                                double count = counter.count();
                                log.info(print.id() + " count=" + print.count(count));
                            },
                            timer -> {
                                double count = timer.count();
                                log.info(print.id() + " count=" + print.count(count) +
                                        " mean=" + print.time(timer.mean(getBaseTimeUnit())));
                            },
                            meter -> log.info(writeMeter(meter, print))
                    );
                });
    }

    String writeMeter(Meter meter, Printer print) {
        return StreamSupport.stream(meter.measure().spliterator(), false)
                .map(ms -> {
                    String msLine = ms.getStatistic().getTagValueRepresentation() + "=";
                    switch (ms.getStatistic()) {
                        case TOTAL:
                        case MAX:
                        case VALUE:
                            return msLine + print.value(ms.getValue());
                        case TOTAL_TIME:
                        case DURATION:
                            return msLine + print.time(ms.getValue());
                        case COUNT:
                            return "count=" + print.count(ms.getValue());
                        default:
                            return msLine + decimalOrNan(ms.getValue());
                    }
                })
                .collect(joining(", ", print.id() + " ", ""));
    }

    @Override
    protected Timer newTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig, PauseDetector pauseDetector) {
        return new CumulativeTimer(id, clock, distributionStatisticConfig, pauseDetector, getBaseTimeUnit(), false);
    }

    @Override
    protected DistributionSummary newDistributionSummary(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig, double scale) {
        return new CumulativeDistributionSummary(id, clock, distributionStatisticConfig, scale, false);
    }

    @Override
    protected Counter newCounter(Meter.Id id) {
        return new CumulativeCounter(id);
    }

    @Override
    protected <T> Gauge newGauge(Meter.Id id, @Nullable T obj, ToDoubleFunction<T> valueFunction) {
        return new DefaultGauge<>(id, obj, valueFunction);
    }

    @Override
    protected <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj, ToLongFunction<T> countFunction, ToDoubleFunction<T> totalTimeFunction, TimeUnit totalTimeFunctionUnit) {
        return new CumulativeFunctionTimer<>(id, obj, countFunction, totalTimeFunction, totalTimeFunctionUnit, getBaseTimeUnit());
    }

    @Override
    protected <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj, ToDoubleFunction<T> countFunction) {
        return new CumulativeFunctionCounter<>(id, obj, countFunction);
    }

    @Override
    protected LongTaskTimer newLongTaskTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig) {
        return new DefaultLongTaskTimer(id, clock, getBaseTimeUnit(), distributionStatisticConfig, false);
    }

    @Override
    protected Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
        return new DefaultMeter(id, type, measurements);
    }

    @Override
    protected DistributionStatisticConfig defaultHistogramConfig() {
        return DistributionStatisticConfig.builder()
                .expiry(Duration.ofMinutes(1))
                .bufferLength(3)
                .build();
    }

    class Printer {
        private final Meter meter;

        Printer(Meter meter) {
            this.meter = meter;
        }

        String id() {
            return getConventionName(meter.getId()) + getConventionTags(meter.getId()).stream()
                    .map(t -> t.getKey() + "=" + t.getValue())
                    .collect(joining(",", "{", "}"));
        }

        String count(double count) {
            return humanReadableBaseUnit(count);
        }

        String unitlessCount(double count) {
            return decimalOrNan(count);
        }

        String time(double time) {
            return TimeUtils.format(Duration.ofNanos((long) TimeUtils.convert(time, getBaseTimeUnit(), TimeUnit.NANOSECONDS)));
        }

        String value(double value) {
            return humanReadableBaseUnit(value);
        }

        // see https://stackoverflow.com/a/3758880/510017
        @SuppressWarnings("StringConcatenationMissingWhitespace")
        String humanReadableByteCount(double bytes) {
            int unit = 1024;
            if (bytes < unit || Double.isNaN(bytes)) {
                return decimalOrNan(bytes) + " B";
            }
            int exp = (int) (Math.log(bytes) / Math.log(unit));
            String pre = "KMGTPE".charAt(exp - 1) + "i";
            return decimalOrNan(bytes / Math.pow(unit, exp)) + " " + pre + "B";
        }

        String humanReadableBaseUnit(double value) {
            String baseUnit = meter.getId().getBaseUnit();
            if (BaseUnits.BYTES.equals(baseUnit)) {
                return humanReadableByteCount(value);
            }
            return decimalOrNan(value) + (baseUnit != null ? " " + baseUnit : "");
        }
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }
}
