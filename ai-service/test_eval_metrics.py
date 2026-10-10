"""Unit tests for the retrieval metrics (precision / recall / MRR / hit-rate).

Tiny hand-computed examples only: one happy path per metric plus the empty
edge (no retrieved rows, no relevant rows) the aggregator depends on.
"""
import os
import sys

# The eval package lives at the repo root, one level above this directory.
# CI runs pytest from ai-service/, so add the root to sys.path first.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from eval.metrics import (
    compute_hit_rate,
    compute_metrics,
    compute_mrr,
    compute_precision_at_k,
    compute_recall_at_k,
)


def test_precision_at_5_happy_path():
    retrieved = ["a.java", "b.java", "c.java", "d.java", "e.java", "f.java"]
    assert compute_precision_at_k(retrieved, {"a.java", "c.java"}, 5) == 2 / 5


def test_recall_at_5_happy_path():
    retrieved = ["a.java", "x.java", "c.java"]
    assert compute_recall_at_k(retrieved, {"a.java", "c.java", "z.java"}, 5) == 2 / 3


def test_mrr_uses_first_relevant_rank():
    assert compute_mrr(["x.java", "y.java", "a.java"], {"a.java"}) == 1 / 3
    assert compute_mrr(["x.java"], {"a.java"}) == 0.0


def test_hit_rate_and_empty_inputs():
    assert compute_hit_rate(["x.java", "a.java"], {"a.java"}, 5) == 1.0
    assert compute_hit_rate(["x.java"], {"a.java"}, 5) == 0.0
    assert compute_precision_at_k([], {"a.java"}, 5) == 0.0
    assert compute_recall_at_k(["a.java"], set(), 5) == 0.0


def test_compute_metrics_bundles_k_values():
    metrics = compute_metrics(["a.java", "b.java"], {"a.java"}, k_values=[5, 10])
    assert metrics["precision@5"] == 1 / 5
    assert metrics["recall@5"] == 1.0
    assert metrics["hit_rate@10"] == 1.0
    assert metrics["mrr"] == 1.0
