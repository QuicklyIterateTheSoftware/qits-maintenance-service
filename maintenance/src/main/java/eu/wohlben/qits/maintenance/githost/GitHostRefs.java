package eu.wohlben.qits.maintenance.githost;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * qits-githost's ref API, id-addressed: list a repository's branches and delete one.
 *
 * <p><b>The one write this service makes on the git host, and it is confined to its own
 * namespace.</b> Everything else here is a READ through {@link GitHostReader}'s name-addressed
 * {@code /git/…} routes, and every commit is a CI step's. A delete needs no clone and no step: it is
 * {@code DELETE /githost/api/repositories/{repoId}/branches/{name}}, which the git host admits for
 * {@code qits:system} — the role {@link PeerClient} presents — and announces as {@code
 * SCMDeleteBranch} like a push would. Its only caller is {@code AutomationBranchSweep}, which only
 * ever names a {@code maintenance/automations/} branch.
 *
 * <p>{@code repoId} is {@code mt_repository.catalog_id}: qits-projects' row id, which is also the git
 * host's storage key.
 */
@ApplicationScoped
public class GitHostRefs {

  static final String REPOSITORIES = "/githost/api/repositories/";

  @Inject PeerClient peers;

  /**
   * A repository's branches, or why not.
   *
   * @param branches the short branch names, empty when unread
   * @param error null when read
   */
  public record Branches(List<String> branches, String error) {
    public boolean ok() {
      return error == null;
    }
  }

  /** How a delete went. */
  public enum Deleted {
    /** The ref is gone now. */
    DELETED,
    /** It was already gone — the idempotent arm. */
    ALREADY_GONE,
    /** Anything else: the sweep asks again next time. */
    FAILED
  }

  /** The outcome and, on FAILED, the sentence. */
  public record Deletion(Deleted outcome, String message) {}

  /** {@code GET /githost/api/repositories/{repoId}} — the describe answer's {@code branches}. */
  public Branches branches(String repoId) {
    PeerAnswer answer = peers.get(PeerTarget.GITHOST, describePath(repoId)).answer();
    if (!answer.ok()) {
      return new Branches(
          List.of(), "the git host could not list the branches of " + repoId + ": " + answer.failure());
    }
    JsonNode body = answer.json();
    if (body == null || !body.has("branches") || !body.get("branches").isArray()) {
      return new Branches(List.of(), "the git host answered no branch list for " + repoId);
    }
    List<String> branches = new ArrayList<>();
    for (JsonNode branch : body.get("branches")) {
      if (branch.isTextual() && !branch.asText().isBlank()) {
        branches.add(branch.asText());
      }
    }
    return new Branches(branches, null);
  }

  /**
   * {@code DELETE …/branches/{name}?projectId=&repoName=} — the pair is what the git host announces
   * the deletion under.
   */
  public Deletion delete(String repoId, String project, String repoName, String branch) {
    PeerAnswer answer =
        peers.delete(PeerTarget.GITHOST, deletePath(repoId, project, repoName, branch)).answer();
    if (answer.ok()) {
      return new Deletion(Deleted.DELETED, null);
    }
    if (answer.notFound() && answer.body() != null && answer.body().contains("no-such-branch")) {
      return new Deletion(Deleted.ALREADY_GONE, null);
    }
    return new Deletion(
        Deleted.FAILED,
        "the git host did not delete " + branch + " of " + repoId + ": " + answer.failure()
            + (answer.body() == null || answer.body().isBlank() ? "" : " " + brief(answer.body())));
  }

  static String describePath(String repoId) {
    return REPOSITORIES + encode(repoId);
  }

  /** The branch is the path's tail and keeps its slashes — the git host reads it that way. */
  static String deletePath(String repoId, String project, String repoName, String branch) {
    StringBuilder path =
        new StringBuilder(REPOSITORIES).append(encode(repoId)).append("/branches/").append(branch);
    String separator = "?";
    if (project != null && !project.isBlank()) {
      path.append(separator).append("projectId=").append(encode(project));
      separator = "&";
    }
    if (repoName != null && !repoName.isBlank()) {
      path.append(separator).append("repoName=").append(encode(repoName));
    }
    return path.toString();
  }

  private static String brief(String body) {
    String flat = body.replace('\n', ' ').trim();
    return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
