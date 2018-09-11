package br.com.cas10.oraman.oracle;

import static com.google.common.collect.Iterables.getOnlyElement;

import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseSystem {

  private final String instanceNumberSql;
  private final String checkExpressEditionSql;
  private final String numCpuCoresSql;
  private final String numCpuThreadsSql;

  @Autowired
  private NamedParameterJdbcTemplate jdbc;

  private int cpuCores;
  private int cpuThreads;
  private long instanceNumber;

  @Autowired
  public DatabaseSystem(SqlFileLoader loader) {
    instanceNumberSql = loader.load("instance_number.sql");
    checkExpressEditionSql = loader.load("check_express_edition.sql");
    numCpuCoresSql = loader.load("num_cpu_cores.sql");
    numCpuThreadsSql = loader.load("num_cpu_threads.sql");
  }

  @PostConstruct
  private void init() {
    JdbcOperations jdbcOps = jdbc.getJdbcOperations();

    instanceNumber = jdbcOps.queryForObject(instanceNumberSql, Long.class);

    int xeQueryResult = jdbcOps.queryForObject(checkExpressEditionSql, Integer.class);
    boolean expressEdition = xeQueryResult > 0;

    if (expressEdition) {
      cpuCores = 1;
      cpuThreads = 1;
    } else {
      cpuThreads = jdbcOps.queryForObject(numCpuThreadsSql, Integer.class);
      // NUM_CPU_CORES is not always available (e.g., cloud environments)
      cpuCores = getOnlyElement(jdbcOps.queryForList(numCpuCoresSql, Integer.class), cpuThreads);
    }
  }

  /**
   * Returns the number of CPU cores available (the value of {@code NUM_CPU_CORES} on
   * {@code v$osstat}). On Express Edition databases, returns {@code 1}.
   *
   * <p>Returns the value of {@code NUM_CPUS} if {@code NUM_CPU_CORES} is not available.
   *
   * @return the number of CPU cores available.
   */
  public int getCpuCores() {
    return cpuCores;
  }

  /**
   * Returns the number of CPUs available (the value of {@code NUM_CPUS} on {@code v$osstat}). On
   * Express Edition databases, returns {@code 1}.
   *
   * @return the number of CPUs available.
   */
  public int getCpuThreads() {
    return cpuThreads;
  }

  /**
   * Returns the ID of the monitored instance ({@code INSTANCE_NUMBER}).
   */
  public long getInstanceNumber() {
    return instanceNumber;
  }
}
