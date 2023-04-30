/*******************************************************************************
 * Copyright (c) 2021, 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;

/**
 * This workspace reader allows to resolve GAV to local workspaceFolders that match
 * instead of usual Maven repos.
 */
public class MavenLemminxWorkspaceReader implements WorkspaceReader {

	private static final int POLLING_INTERVAL = 30;

	private static final Logger LOGGER = Logger.getLogger(MavenLemminxExtension.class.getName());
	
	private final class ResolveArtifactsAndPopulateWorkspaceRunnable implements Runnable {
		final File pomFile;

		private ResolveArtifactsAndPopulateWorkspaceRunnable(File pomFile) {
			this.pomFile = pomFile;
		}

		@Override
		public void run() {
			// already processed, don't repeat operation
			if (!workspaceArtifacts.containsValue(pomFile)) {
				LOGGER.finest("Trying to add " + pomFile + "to workspace...");
				skipFlushBeforeResult.set(true); // avoid deadlock as building project will go through this workspace reader
				Optional<MavenProject> snapshotProject = Optional.empty();
				try {
					snapshotProject = plugin.getProjectCache().getSnapshotProject(pomFile);
				} catch (Exception e) {
					// We shouldn't fail here, otherwise, the pomFile will never be processed 
					// causing a possible deadlock in "Flush Before Result" loops
					LOGGER.fine(e.getMessage());
				} finally {
					skipFlushBeforeResult.set(false);
				}
				snapshotProject.ifPresentOrElse(project -> {
					while (project != null) { 
						File pom = project.getFile();
						if (toProcess.contains(pom)) {
							DefaultArtifact artifact = new DefaultArtifact(project.getGroupId(), project.getArtifactId(), null, project.getVersion());
							workspaceArtifacts.put(artifact, pom);
							LOGGER.finest("Registered" + artifact + " -> " + pom + " into workspace...");
						}
						propagateProcessed(pom);
						project = project.getParent();
					}
				}, () -> { // try reading GAV at least
					try {
						String fileContent = String.join(System.lineSeparator(), Files.readAllLines(pomFile.toPath()));
						
						DOMDocument doc = DOMParser.getInstance().parse(new TextDocument(fileContent, pomFile.toURI().toString()), null);
						doc.getChildren().stream() //
							.filter(DOMElement.class::isInstance) //
							.map(DOMElement.class::cast) //
							.filter(node -> DOMConstants.PROJECT_ELT.equals(node.getLocalName())) //
							.forEach(projectNode -> {
								Optional<DOMElement> parentElement = DOMUtils.findChildElement(projectNode, DOMConstants.PARENT_ELT);
								Optional<String> groupId = DOMUtils.findChildElementText(projectNode, DOMConstants.GROUP_ID_ELT)
										.or(() -> parentElement.flatMap(parent -> DOMUtils.findChildElementText(parent, DOMConstants.GROUP_ID_ELT)));
								Optional<String> artifactId = DOMUtils.findChildElementText(projectNode, DOMConstants.ARTIFACT_ID_ELT);
								Optional<String> version = DOMUtils.findChildElementText(projectNode, DOMConstants.VERSION_ELT)
										.or(() -> parentElement.flatMap(parent -> DOMUtils.findChildElementText(parent, DOMConstants.VERSION_ELT)));
								if (groupId.isPresent() && !groupId.get().contains("$") && //
									artifactId.isPresent() && !artifactId.get().contains("$") &&
									version.isPresent() && !version.get().contains("$")) {
									DefaultArtifact artifact = new DefaultArtifact(groupId.get(), artifactId.get(), null, version.get());
									workspaceArtifacts.put(artifact, pomFile);
									LOGGER.finest("Registered" + artifact + " -> " + pomFile + " into workspace...");
								}
							});
					} catch (IOException ex) {
						LOGGER.fine(ex.getMessage());
					}
				});
			}
			LOGGER.finest("Done adding " + pomFile + "to workspace...");
			// ensure we remove it from further processing even in case no MavenProject can be built
			propagateProcessed(pomFile);
		}

		private void propagateProcessed(File pom) {
			toProcess.remove(pom); // mark this pom done 
			// remove all other scheduled runnable for the given file
			runnables.removeIf(runnable -> ((ResolveArtifactsAndPopulateWorkspaceRunnable)runnable).pomFile.equals(pom));
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == null) {
				return false;
			}
			if (obj.getClass() != this.getClass()) {
				return false;
			}
			return Objects.equals(pomFile, ((ResolveArtifactsAndPopulateWorkspaceRunnable)obj).pomFile);
		}

		@Override
		public int hashCode() {
			return Objects.hash(pomFile);
		}
	}

	private static final Comparator<Runnable> DEEPEST_FIRST = (o1, o2) -> {
		if (!(o1 instanceof ResolveArtifactsAndPopulateWorkspaceRunnable && o2 instanceof ResolveArtifactsAndPopulateWorkspaceRunnable)) {
			return 0;
		}
		return ((ResolveArtifactsAndPopulateWorkspaceRunnable)o2).pomFile.getAbsolutePath().length() - ((ResolveArtifactsAndPopulateWorkspaceRunnable)o1).pomFile.getAbsolutePath().length();
	};

	private final WorkspaceRepository repository;
	private final MavenLemminxExtension plugin;
	
	// Don't add any sorting here, the files are to be processed exactly in the order they added.
	// Any sorting is to be done before adding to this set
	//
	private Set<File> toProcess = Collections.synchronizedSet(new LinkedHashSet<File>());
	
	private ThreadLocal<Boolean> skipFlushBeforeResult = new ThreadLocal<>();
	private final PriorityBlockingQueue</*ResolveArtifactsAndPopulateWorkspaceRunnable*/Runnable> runnables = new PriorityBlockingQueue<>(1, DEEPEST_FIRST);
	private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, runnables);

	private Map<Artifact, File> workspaceArtifacts = new ConcurrentHashMap<>();
	
	public MavenLemminxWorkspaceReader(MavenLemminxExtension plugin) {
		this.plugin = plugin;
		repository = new WorkspaceRepository("workspace");
		skipFlushBeforeResult.set(false);
	}

	@Override
	public WorkspaceRepository getRepository() {
		return repository;
	}

	@Override
	public File findArtifact(Artifact artifact) {
		String projectKey = ArtifactUtils.key(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
		return plugin.getProjectCache().getProjects().stream()
				.filter(p -> p.getArtifact() != null && projectKey.equals(ArtifactUtils.key(p.getArtifact())))
				.map(project -> {
					File file = find(project, artifact);
					if (file == null && project != project.getExecutionProject()) {
						file = find(project.getExecutionProject(), artifact);
					}
					return file;
				}).filter(Objects::nonNull)
				.findAny()
				.or(() -> {
					if (skipFlushBeforeResult.get() != Boolean.TRUE) {
						LOGGER.finest("Waiting for " + projectKey + " to be avilable; processing workspace in the meantime...");
						while (!toProcess.isEmpty() && getCurrentWorkspaceArtifact(projectKey).isEmpty()) {
							try {
								Thread.sleep(POLLING_INTERVAL);
							} catch (InterruptedException e) {
								LOGGER.severe(e.getMessage());
							}
						}
						LOGGER.finest("Done waiting from " + projectKey + ". Either found, or all workspace processed.");
					}
					return getCurrentWorkspaceArtifact(projectKey);
				}).orElse(null);
	}

	private Optional<File> getCurrentWorkspaceArtifact(String projectKey) {
		return workspaceArtifacts.entrySet().stream() //
			.filter(entry -> ArtifactUtils.key(entry.getKey().getGroupId(), entry.getKey().getArtifactId(), entry.getKey().getVersion()).equals(projectKey))
			.map(Entry::getValue)
			.findAny();
	}
 
	@Override
	public List<String> findVersions(Artifact artifact) {
		if (skipFlushBeforeResult.get() != Boolean.TRUE) {
			LOGGER.finest("Lookup available versions for " + artifact + "; processing workspace in the meantime...");
			while (!toProcess.isEmpty()) {
				try {
					Thread.sleep(POLLING_INTERVAL);
				} catch (InterruptedException e) {
					LOGGER.severe(e.getMessage());
				}
			}
			LOGGER.finest("Workspace processing complete");
		}
		String key = ArtifactUtils.versionlessKey(artifact.getGroupId(), artifact.getArtifactId());
		SortedSet<String> res = new TreeSet<>(Comparator.reverseOrder());
		plugin.getProjectCache().getProjects().stream() //
				.filter(p -> p.getArtifact() != null && key.equals(ArtifactUtils.versionlessKey(p.getArtifact()))) //
				.filter(p -> find(p, artifact) != null) //
				.map(MavenProject::getVersion) //
				.forEach(res::add);
		workspaceArtifacts.entrySet().stream() //
				.filter(entry -> Objects.equals(key, ArtifactUtils.versionlessKey(entry.getKey().getGroupId(), entry.getKey().getArtifactId())))
				.map(Entry::getKey)
				.map(Artifact::getVersion)
				.forEach(res::add);
		return new ArrayList<>(res);
	}
	
	private File find(MavenProject project, Artifact artifact) {
		if ("pom".equals(artifact.getExtension())) {
			return project.getFile();
		}

		Artifact projectArtifact = findMatchingArtifact(project, artifact);
		if (hasArtifactFileFromPackagePhase(projectArtifact)) {
			return projectArtifact.getFile();
		}
		return null;
	}
	
	private boolean hasArtifactFileFromPackagePhase(Artifact projectArtifact) {
		return projectArtifact != null && projectArtifact.getFile() != null && projectArtifact.getFile().exists();
	}
	
	private Artifact findMatchingArtifact(MavenProject project, Artifact requestedArtifact) {
		String requestedRepositoryConflictId = ArtifactIdUtils.toVersionlessId(requestedArtifact);
		Artifact mainArtifact = RepositoryUtils.toArtifact(project.getArtifact());
		if (requestedRepositoryConflictId.equals(ArtifactIdUtils.toVersionlessId(mainArtifact))) {
			return mainArtifact;
		}

		for (Artifact attachedArtifact : RepositoryUtils.toArtifacts(project.getAttachedArtifacts())) {
			if (attachedArtifactComparison(requestedArtifact, attachedArtifact)) {
				return attachedArtifact;
			}
		}
		return null;
	}
	
	private boolean attachedArtifactComparison(Artifact requested, Artifact attached) {
		return requested.getArtifactId().equals(attached.getArtifactId())
				&& requested.getGroupId().equals(attached.getGroupId())
				&& requested.getVersion().equals(attached.getVersion())
				&& requested.getExtension().equals(attached.getExtension())
				&& requested.getClassifier().equals(attached.getClassifier());
	}

	/**
	 * Parses and adds a document for a given URI into the projects cache
	 * Any sorting is to be done before the method is invoked.
	 * 
	 * @param uri An URI of a document to add
	 * @param documents documents to add
	 */
	public void addToWorkspace(Collection<URI> uris) {
		uris.stream()
			.map(File::new)
			.filter(File::isFile)
			.filter(file -> !workspaceArtifacts.values().contains(file)) // ignore already processed
			.forEach(toProcess::add);
		for (File file : toProcess.stream().collect(Collectors.toSet())) {
			executor.execute(new ResolveArtifactsAndPopulateWorkspaceRunnable(file));
		}
	}

	public void remove(URI uri) {
		workspaceArtifacts.values().remove(new File(uri));
	}
}
