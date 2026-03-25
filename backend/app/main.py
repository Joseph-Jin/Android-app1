from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import load_settings
from app.api.routes.notes import router as notes_router
from app.api.routes.health import router as health_router
from app.services.note_job_service import NoteJobService


@asynccontextmanager
async def lifespan(app: FastAPI):
    await app.state.note_job_service.recover_processing_jobs()
    yield


app = FastAPI(lifespan=lifespan)
app.state.note_job_service = NoteJobService(data_dir=load_settings().data_dir)
app.include_router(health_router)
app.include_router(notes_router)
