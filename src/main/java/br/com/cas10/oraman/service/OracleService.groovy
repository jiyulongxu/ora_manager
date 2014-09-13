package br.com.cas10.oraman.service

import groovy.transform.CompileStatic

import java.sql.ResultSet
import java.sql.SQLException

import javax.sql.DataSource

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import br.com.cas10.oraman.analitics.ActiveSession

@Service
@Transactional(readOnly = true)
@CompileStatic
class OracleService {

  private NamedParameterJdbcTemplate jdbc

  @Autowired
  @Qualifier('monitoring')
  void setDataSource(DataSource dataSource) {
    jdbc = new NamedParameterJdbcTemplate(dataSource)
  }

  List<Map<String,Object>> getWaits() {
    String query = '''
			select wait_class as eventclass, sum(time_waited_micro) as eventtime
			from v$system_event where wait_class <> 'Idle' group by wait_class

			union all

			select 'CPU', sum(value) from v$sys_time_model where stat_name in ('DB CPU', 'background cpu time')
			'''
    List<Map<String,Object>> result = jdbc.queryForList(query, Collections.emptyMap())
    return result
  }

  List<ActiveSession> getActiveSessions() {
    String query = '''
			select
				sid,
				serial#,
				decode(type, 'BACKGROUND', substr(program, -5, 4), username) as username,
				program,
				sql_id,
				sql_child_number,
				decode(wait_time, 0, event, 'CPU + CPU Wait') as event,
				decode(wait_time, 0, wait_class, 'CPU + CPU Wait') as wait_class
			from
				v\$session
			where
				(program <> 'OraManager' or program is null)
				and ((wait_time <> 0 and status = 'ACTIVE') or wait_class <> 'Idle')
			'''
    List<ActiveSession> result = jdbc.query(query, Collections.emptyMap(), new RowMapper<ActiveSession>() {
          ActiveSession mapRow(ResultSet rs, int rowNum) throws SQLException {
            ActiveSession s = new ActiveSession()
            s.sid = rs.getString('sid').intern()
            s.serialNumber = rs.getString('serial#').intern()
            s.username = rs.getString('username')?.intern()
            s.program = rs.getString('program')?.intern()
            s.sqlId = rs.getString('sql_id')?.intern()
            s.sqlChildNumber = rs.getString('sql_child_number')?.intern()
            s.event = rs.getString('event').intern()
            s.waitClass = rs.getString('wait_class').intern()
            return s
          }
        })
    return result
  }

  Map getSession(Long sid, Long serialNumber) {
    String query = 'select username, program from v$session where sid = :sid and serial# = :serialNumber'
    List<Map> result = jdbc.queryForList(query, ['sid' : sid, 'serialNumber' : serialNumber])
    return result ? result.first() : null
  }
}
