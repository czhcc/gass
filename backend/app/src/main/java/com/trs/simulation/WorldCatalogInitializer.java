package com.trs.simulation;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Seeds two reusable demo worlds after schema migrations without creating duplicates. @author czhcc-trsdsj\codexsandboxoffline */
@Component
public class WorldCatalogInitializer implements ApplicationRunner {
  private final WorldService worlds;

  /** Receives the world catalog. @author czhcc-trsdsj\codexsandboxoffline */
  public WorldCatalogInitializer(WorldService worlds) {
    this.worlds = worlds;
  }

  /** Initializes missing demo templates and preserves later edits. @author czhcc-trsdsj\codexsandboxoffline */
  @Override
  public void run(ApplicationArguments args) {
    worlds.example("information");
    worlds.example("bank");
  }
}
