package io.jethro.refdata;

import io.jethro.domain.Book;
import io.jethro.domain.BookId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookTreeTest {

    private static Book book(String id, String parent) {
        return new Book(new BookId(id), id + " desk", "USD",
                Optional.ofNullable(parent).map(BookId::new));
    }

    @Test
    void assemblesSeedShapedTree() {
        var roots = BookTree.assemble(List.of(
                book("FIRM", null), book("ALPHA", "FIRM"), book("BETA", "FIRM")));
        assertEquals(1, roots.size());
        assertEquals("FIRM", roots.get(0).bookId());
        assertEquals(List.of("ALPHA", "BETA"),
                roots.get(0).children().stream().map(BookTree.Node::bookId).toList());
    }

    @Test
    void orphanParentSurfacesAsRootNeverDisappears() {
        var roots = BookTree.assemble(List.of(book("LOST", "MISSING-PARENT")));
        assertEquals(1, roots.size());
        assertEquals("LOST", roots.get(0).bookId());
    }

    @Test
    void nestedTree() {
        var roots = BookTree.assemble(List.of(
                book("FIRM", null), book("EQ", "FIRM"), book("EQ-US", "EQ")));
        assertEquals("EQ-US", roots.get(0).children().get(0).children().get(0).bookId());
    }
}
