package br.com.cas10.oraman.oracle.data;

import java.util.Date;
import java.util.LinkedHashMap;

public class Table {

  public String owner;
  public String name;
  public String tablespace;
  public Long rows;
  public Date lastAnalyzed;

  public LinkedHashMap<String, Column> columns = new LinkedHashMap<>();
  public LinkedHashMap<String, Index> indexes = new LinkedHashMap<>();

  public Double dataSizeMb;
  public Double lobSizeMb;
  public Double indexSizeMb;

}
