from __future__ import annotations

from fastapi import APIRouter, File, Form, Request, UploadFile


router = APIRouter(prefix="/api/note-jobs")


@router.post("", status_code=202)
async def create_note_job(
    request: Request,
    card_id: str = Form(..., alias="cardId"),
    audio: UploadFile = File(...),
) -> dict[str, object]:
    service = request.app.state.note_job_service
    record = await service.create_job(audio, card_id=card_id)
    return {"status": record.status, "jobId": record.job_id}


@router.get("/{job_id}")
def get_note_job(request: Request, job_id: str) -> dict[str, object]:
    service = request.app.state.note_job_service
    return service.get_job(job_id).to_response()
