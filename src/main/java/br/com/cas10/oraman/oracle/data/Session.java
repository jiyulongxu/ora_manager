package br.com.cas10.oraman.oracle.data;

import java.util.List;

public class Session {

  public long sid;
  public long serialNumber;
  public String username;
  public String program;

  public GlobalSession blockingSession;
  public List<LockedObject> lockedObjects;

}
