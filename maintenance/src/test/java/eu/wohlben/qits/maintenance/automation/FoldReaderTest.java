package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * <b>{@link FoldReader} reads a listing once</b> (qits-1697), as it reads a file once (qits-1258),
 * over a fake {@link GitHostReader} that counts its calls: a FOUND or ABSENT listing is a fact about
 * the commit and is kept; an UNREACHABLE one is a failed read and is asked again.
 */
class FoldReaderTest {

  private static final String SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";

  /** A git host answering one listing per call, and recording each call it was asked. */
  private static final class Host extends GitHostReader {

    final List<String> calls = new CopyOnWriteArrayList<>();

    TreeLookup tree = TreeLookup.found(SHA, List.of(new TreeLookup.TreeEntry("pom.xml", "blob")));

    TreeLookup recursive = tree;

    @Override
    public TreeLookup tree(String project, String repository, String revision, String path) {
      calls.add("tree:" + path);
      return tree;
    }

    @Override
    public TreeLookup recursiveTree(String project, String repository, String revision) {
      calls.add("recursive");
      return recursive;
    }

    @Override
    public FileLookup blob(String project, String repository, String revision, String path) {
      calls.add("blob:" + path);
      return FileLookup.absent();
    }
  }

  private static FoldReader reader(Host host) {
    return new FoldReader(host, "qits", "repo-under-test", SHA);
  }

  @Test
  void aFoundListingIsReadOnce() {
    Host host = new Host();
    FoldReader fold = reader(host);

    TreeLookup first = fold.tree("");
    assertSame(first, fold.tree(""));
    assertSame(first, fold.tree(null), "null is the root, the same listing");
    TreeLookup whole = fold.recursiveTree();
    assertSame(whole, fold.recursiveTree());

    assertEquals(List.of("tree:", "recursive"), host.calls);
  }

  @Test
  void anAbsentListingIsAFactAndKept() {
    Host host = new Host();
    host.tree = TreeLookup.absent();
    FoldReader fold = reader(host);

    fold.tree("docs/database");
    fold.tree("docs/database");

    assertEquals(List.of("tree:docs/database"), host.calls);
  }

  /** A host that ignores {@code ?recursive=true} answers its plain listing — a fact for the sha. */
  @Test
  void aPlainAnswerToARecursiveAskIsKept() {
    Host host = new Host();
    FoldReader fold = reader(host);

    assertFalse(fold.recursiveTree().recursive());
    assertFalse(fold.recursiveTree().recursive());

    assertEquals(List.of("recursive"), host.calls);
  }

  @Test
  void anUnreachableListingIsAskedAgain() {
    Host host = new Host();
    host.tree = TreeLookup.unreachable("git host down");
    host.recursive = TreeLookup.unreachable("git host down");
    FoldReader fold = reader(host);

    fold.tree("");
    fold.recursiveTree();
    host.tree = TreeLookup.found(SHA, List.of());
    host.recursive = TreeLookup.foundRecursive(SHA, List.of());

    assertEquals(FileLookup.Status.FOUND, fold.tree("").status());
    assertEquals(FileLookup.Status.FOUND, fold.recursiveTree().status());
    assertEquals(List.of("tree:", "recursive", "tree:", "recursive"), host.calls);
  }
}
