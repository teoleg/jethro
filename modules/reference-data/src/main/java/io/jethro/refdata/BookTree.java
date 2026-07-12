package io.jethro.refdata;

import io.jethro.domain.Book;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assembles the flat book list into the tree the Book Structure view renders (ADR-0008). */
public final class BookTree {

    public record Node(String bookId, String name, String baseCurrency, List<Node> children) {
    }

    private BookTree() {
    }

    /** Roots (books without a parent) with children nested; unknown parents surface as roots. */
    public static List<Node> assemble(List<Book> books) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Book book : books) {
            nodes.put(book.id().value(),
                    new Node(book.id().value(), book.name(), book.baseCurrency(), new ArrayList<>()));
        }
        List<Node> roots = new ArrayList<>();
        for (Book book : books) {
            Node node = nodes.get(book.id().value());
            var parent = book.parentId().map(id -> nodes.get(id.value())).orElse(null);
            if (parent == null) {
                roots.add(node); // true root, or orphan (parent not in set) — visible either way
            } else {
                parent.children().add(node);
            }
        }
        return roots;
    }
}
