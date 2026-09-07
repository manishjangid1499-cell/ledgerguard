package com.ledgerguard.e2e.infrastructure;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;

public class ApplicationContainer extends GenericContainer<ApplicationContainer> {

    private static final String DEFAULT_IMAGE = "eclipse-temurin:21-jre";

    public ApplicationContainer(Path jarPath, String... extraArgs) {
        super(DEFAULT_IMAGE);
        withCopyFileToContainer(MountableFile.forHostPath(jarPath), "/app/app.jar");
        List<String> cmd = new ArrayList<>(List.of("java", "-Xms128m", "-Xmx384m", "-jar", "/app/app.jar"));
        if (extraArgs != null && extraArgs.length > 0) {
            cmd.addAll(List.of(extraArgs));
        }
        withCommand(cmd.toArray(new String[0]));
    }
}
