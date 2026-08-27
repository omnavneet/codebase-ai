from sentence_transformers import SentenceTransformer
import os
from dotenv import load_dotenv

load_dotenv()

class EmbeddingService:
    _instance = None
    _model = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance
    
    def __init__(self):
        if self._model is None:
            model_name = os.getenv("EMBEDDING_MODEL")
            print(f"Loading embedding model: {model_name}")
            self._model = SentenceTransformer(model_name)
            print("Model loaded successfully")
    
    def generate_embeddings(self, texts):
        """Generate embeddings for list of texts"""
        embeddings = self._model.encode(texts, normalize_embeddings=True)
        return embeddings.tolist()
    
    def generate_single_embedding(self, text):
        """Generate embedding for single text"""
        embedding = self._model.encode(text, normalize_embeddings=True)
        return embedding.tolist()