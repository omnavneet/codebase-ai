from sentence_transformers import SentenceTransformer
import os
from dotenv import load_dotenv

load_dotenv()

# The database column is chunks.embedding vector(384); a model with a different
# dimension would fail every insert with a confusing database error, so the
# mismatch is rejected here with a clear message instead.
EXPECTED_EMBEDDING_DIM = 384


class EmbeddingService:
    _instance = None
    _model = None
    _initialized = False

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        if self._initialized:
            return

        model_name = os.getenv("EMBEDDING_MODEL", "all-MiniLM-L6-v2")
        print(f"Loading embedding model: {model_name}")
        self._model = SentenceTransformer(model_name)
        self.model_name = model_name
        self.embedding_dim = self._model.get_sentence_embedding_dimension()
        print("Model loaded successfully")

        if self.embedding_dim != EXPECTED_EMBEDDING_DIM:
            raise RuntimeError(
                f"EMBEDDING_MODEL '{model_name}' produces {self.embedding_dim}-dimensional vectors, "
                f"but the database stores vector({EXPECTED_EMBEDDING_DIM}). "
                "Change the model or the chunks.embedding column, never just one of them."
            )

        self._initialized = True

    def generate_embeddings(self, texts):
        """Generate embeddings for list of texts"""
        embeddings = self._model.encode(texts, normalize_embeddings=True)
        return embeddings.tolist()