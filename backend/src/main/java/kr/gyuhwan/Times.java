package kr.gyuhwan;
import java.time.Instant;import java.time.format.DateTimeFormatterBuilder;
/** DB와 감사 기록에 쓰는 시각을 밀리초 단위 UTC ISO-8601 문자열로 통일한다. */
final class Times{static String stamp(Instant value){return new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value);}}
