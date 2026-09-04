package org.km.llmwiki.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceStartupLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceStartupLoader.class);

    private final WorkspaceService workspaceService;
    private final WorkspaceRepository workspaceRepository;

    public WorkspaceStartupLoader(WorkspaceService workspaceService, WorkspaceRepository workspaceRepository) {
        this.workspaceService = workspaceService;
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            workspaceRepository.enforceSingleActive();
        } catch (Exception exception) {
            log.error("Could not enforce single active workspace invariant", exception);
            return;
        }
        try {
            WorkspaceStatusResponse current = workspaceService.current();
            log.info("Loaded existing workspace '{}' at root {} (layout valid: {}, repaired directories: {})",
                    current.workspace().name(),
                    current.workspace().rootPath(),
                    current.layout().valid(),
                    current.layout().repairedDirectories());
        } catch (NoActiveWorkspaceException exception) {
            log.info("No existing workspace registered yet; waiting for initialization");
        } catch (Exception exception) {
            log.error("Failed to load existing workspace on startup", exception);
        }
    }
}
