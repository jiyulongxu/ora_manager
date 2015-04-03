package br.com.cas10.oraman.agent.ash;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.cas10.oraman.agent.ash.AshArchive.ArchivedSnapshotsIterator;
import br.com.cas10.oraman.oracle.Cursors;
import br.com.cas10.oraman.oracle.data.ActiveSession;
import br.com.cas10.oraman.oracle.data.Cursor;
import br.com.cas10.oraman.util.Snapshot;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Ordering;
import com.google.common.collect.Table;

@Service
public class Ash {

  @Autowired
  private AshAgent agent;
  @Autowired
  private AshArchive archive;
  @Autowired
  private Cursors cursors;

  public List<String> getWaitClasses() {
    return agent.getWaitClasses();
  }

  /**
   * @return snapshots with the average active sessions by wait class, calculated from the ASH
   *         snapshots in memory.
   */
  @Transactional(readOnly = true)
  public List<Snapshot<Double>> getWaitClassesSnapshots() {
    List<AshSnapshot> snapshots = agent.getSnapshots();
    List<Snapshot<Double>> waitClassesSnapshots = new ArrayList<>(snapshots.size());
    snapshots.forEach(s -> waitClassesSnapshots.add(s.waitClassesSnapshot));
    return waitClassesSnapshots;
  }

  /**
   * Returns the activity data for the specified interval.
   * <p>
   * The activity data is taken from the ASH snapshots currently in memory whose timestamp is in the
   * interval {@code [start, end]}. The returned object contains
   * <ul>
   * <li>snapshots with the average active sessions by wait class,</li>
   * <li>top 10 SQL statements in the interval,</li>
   * <li>top 10 sessions in the interval.</li>
   * </ul>
   *
   * @param start interval start.
   * @param end interval end.
   * @return the activity data for the specified interval.
   */
  @Transactional(readOnly = true)
  public IntervalActivity getIntervalActivity(long start, long end) {
    List<AshSnapshot> snapshots = agent.getSnapshots();
    return intervalActivity(snapshots.iterator(), start, end);
  }


  /**
   * Loads and returns from the disk archive the activity data for the 1-hour interval
   * {@code year/month/dayOfMonth [hour, hour + 1)}.
   * <p>
   * The returned object contains
   * <ul>
   * <li>snapshots with the average active sessions by wait class,</li>
   * <li>top 10 SQL statements in the interval,</li>
   * <li>top 10 sessions in the interval.</li>
   * </ul>
   *
   * @param year the year.
   * @param month the month-of-year, from 1 to 12.
   * @param dayOfMonth the day-of-month, from 1 to 31.
   * @param hour the start of the 1-hour interval, from 0 to 23.
   * @return the activity data for the specified interval.
   */
  @Transactional(readOnly = true)
  public IntervalActivity getIntervalActivity(int year, int month, int dayOfMonth, int hour) {
    ZonedDateTime startDateTime =
        LocalDateTime.of(year, month, dayOfMonth, hour, 0).atZone(ZoneId.systemDefault());
    ZonedDateTime endDateTime = startDateTime.plusHours(1);
    if (endDateTime.getHour() == hour) {
      endDateTime = endDateTime.plusHours(1); // daylight saving time
    }
    try (ArchivedSnapshotsIterator it = archive.getArchivedSnapshots(year, month, dayOfMonth, hour)) {
      long start = startDateTime.toInstant().toEpochMilli();
      long end = endDateTime.toInstant().toEpochMilli() - 1;
      return intervalActivity(it, start, end);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private IntervalActivity intervalActivity(Iterator<AshSnapshot> snapshots, long start, long end) {
    int totalSamples = 0;
    int totalActivity = 0;
    List<Snapshot<Double>> waitClassesSnapshots = new ArrayList<>();
    Map<String, SqlActivity.Builder> sqlMap = new HashMap<>();
    Table<String, String, SessionActivity.Builder> sessionsTable = HashBasedTable.create();

    while (snapshots.hasNext()) {
      AshSnapshot snapshot = snapshots.next();
      if (snapshot.timestamp < start || snapshot.timestamp > end) {
        continue;
      }
      totalSamples += snapshot.samples;
      totalActivity += snapshot.activeSessions.size();
      waitClassesSnapshots.add(snapshot.waitClassesSnapshot);
      for (ActiveSession s : snapshot.activeSessions) {
        sqlMap.computeIfAbsent(s.sqlId, SqlActivity.Builder::new).add(s);

        SessionActivity.Builder sessBuilder = sessionsTable.get(s.sid, s.serialNumber);
        if (sessBuilder == null) {
          sessBuilder = new SessionActivity.Builder(s.sid, s.serialNumber, s.username, s.program);
          sessionsTable.put(s.sid, s.serialNumber, sessBuilder);
        }
        sessBuilder.add(s);
      }
    }

    Ordering<SqlActivity.Builder> sqlOrdering =
        Ordering.from((a, b) -> Integer.compare(a.getActivity(), b.getActivity()));
    List<SqlActivity> topSql = new ArrayList<>();
    for (SqlActivity.Builder builder : sqlOrdering.greatestOf(sqlMap.values(), 10)) {
      Cursor cursor = builder.getSqlId() == null ? null : cursors.getCursor(builder.getSqlId());
      String sqlText = cursor == null ? null : cursor.sqlText;
      String command = cursor == null ? null : cursor.command;
      topSql.add(builder.build(sqlText, command, totalActivity, totalSamples));
    }

    Ordering<SessionActivity.Builder> sessionsOrdering =
        Ordering.from((a, b) -> Integer.compare(a.getActivity(), b.getActivity()));
    List<SessionActivity> topSessions = new ArrayList<>();
    for (SessionActivity.Builder builder : sessionsOrdering.greatestOf(sessionsTable.values(), 10)) {
      topSessions.add(builder.build(totalActivity));
    }

    return new IntervalActivity(start, end, waitClassesSnapshots, topSql, topSessions);
  }

  /**
   * Returns the average activity of the specified parent cursor by wait event.
   *
   * @param sqlId {@code v$sql:sql_id}
   * @return a list of snapshots
   */
  public List<Snapshot<Double>> getSqlSnapshots(String sqlId) {
    checkNotNull(sqlId);
    return buildEventsSnapshots(s -> sqlId.equals(s.sqlId));
  }

  /**
   * Returns the average activity of the specified session by wait event.
   *
   * @param sessionId {@code v$session:sid}
   * @param serialNumber {@code v$session:serial#}
   * @return a list of snapshots
   */
  public List<Snapshot<Double>> getSessionSnapshots(long sessionId, long serialNumber) {
    String sidStr = Long.toString(sessionId);
    String serialNumberStr = Long.toString(serialNumber);
    return buildEventsSnapshots(s -> sidStr.equals(s.sid) && serialNumberStr.equals(s.serialNumber));
  }

  private List<Snapshot<Double>> buildEventsSnapshots(Predicate<ActiveSession> activeSessionFilter) {
    List<AshSnapshot> snapshots = agent.getSnapshots();

    List<Snapshot<Double>> eventsSnapshots = new ArrayList<>(snapshots.size());
    for (AshSnapshot snapshot : snapshots) {
      Multiset<String> events =
          snapshot.activeSessions.stream().filter(activeSessionFilter).map(s -> s.event)
              .collect(toCollection(HashMultiset::create));

      Map<String, Double> values =
          events.entrySet().stream()
              .collect(toMap(e -> e.getElement(), e -> (double) e.getCount() / snapshot.samples));

      eventsSnapshots.add(new Snapshot<>(snapshot.timestamp, values));
    }
    return eventsSnapshots;
  }
}
