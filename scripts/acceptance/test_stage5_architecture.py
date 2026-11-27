"""Check the stage-five package direction on the production Java sources."""
from pathlib import Path
import re
import unittest


def import_graph(root):
    sources = {path: path.read_text(encoding="utf-8") for path in root.rglob("*.java")}
    packages = {path: re.search(r"^package ([\w.]+);", source, re.M)[1] for path, source in sources.items()}
    classes = {f"{package}.{path.stem}": package for path, package in packages.items()}
    graph = {package: set() for package in packages.values()}
    for path, source in sources.items():
        package = packages[path]
        for symbol in re.findall(r"^import (?:static )?(com\.trs\.simulation[\w.]+);", source, re.M):
            while symbol and symbol not in classes:
                symbol = symbol.rpartition(".")[0]
            if symbol in classes and classes[symbol] != package:
                graph[package].add(classes[symbol])
    return graph


def cycle(graph, origin, path):
    for target in sorted(graph[path[-1]]):
        if target == origin:
            return [*path, target]
        if target not in path and (witness := cycle(graph, origin, [*path, target])):
            return witness
    return None


class KnowledgeArchitectureTest(unittest.TestCase):
    def test_shared_contract_and_knowledge_implementation_do_not_form_package_cycles(self):
        repo = Path(__file__).resolve().parents[2]
        graph = import_graph(repo / "backend/app/src/main/java/com/trs/simulation")
        self.assertIsNone(cycle(graph, "com.trs.simulation.knowledge", ["com.trs.simulation.knowledge"]))
        package = "com.trs.simulation.contract"
        self.assertIn(package, graph)
        self.assertFalse(graph[package] - {package})


if __name__ == "__main__":
    unittest.main()
