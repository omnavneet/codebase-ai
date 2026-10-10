from typing import List, Set, Dict, Any


def compute_precision_at_k(retrieved: List[str], relevant: Set[str], k: int) -> float:
    """Precision@k: fraction of top-k retrieved that are relevant."""
    retrieved_top_k = retrieved[:k]
    if not retrieved_top_k:
        return 0.0
    hits = len([f for f in retrieved_top_k if f in relevant])
    return hits / k


def compute_recall_at_k(retrieved: List[str], relevant: Set[str], k: int) -> float:
    """Recall@k: fraction of relevant items found in top-k retrieved."""
    if not relevant:
        return 0.0
    retrieved_top_k = retrieved[:k]
    hits = len([f for f in retrieved_top_k if f in relevant])
    return hits / len(relevant)


def compute_mrr(retrieved: List[str], relevant: Set[str]) -> float:
    """Mean Reciprocal Rank: 1/rank of first relevant item, or 0 if none found."""
    for rank, f in enumerate(retrieved, start=1):
        if f in relevant:
            return 1.0 / rank
    return 0.0


def compute_hit_rate(retrieved: List[str], relevant: Set[str], k: int) -> float:
    """Hit rate@k: whether at least one relevant item is in top-k."""
    retrieved_top_k = retrieved[:k]
    return 1.0 if any(f in relevant for f in retrieved_top_k) else 0.0


def compute_metrics(
    retrieved: List[str],
    relevant: Set[str],
    k_values: List[int] = None,
) -> Dict[str, Any]:
    """Compute precision, recall, MRR, and hit-rate for given k values."""
    if k_values is None:
        k_values = [5, 10]
    metrics = {}
    for k in k_values:
        metrics[f"precision@{k}"] = compute_precision_at_k(retrieved, relevant, k)
        metrics[f"recall@{k}"] = compute_recall_at_k(retrieved, relevant, k)
        metrics[f"hit_rate@{k}"] = compute_hit_rate(retrieved, relevant, k)
    metrics["mrr"] = compute_mrr(retrieved, relevant)
    return metrics