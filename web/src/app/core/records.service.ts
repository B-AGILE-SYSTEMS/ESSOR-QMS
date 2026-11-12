import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  Attachment, DashboardSummary, HistoryEntry, MyTask, NewAction, NewRecord, Page, QmsRecord, RecordPatch, RecordQuery,
  RecordSummary, RecordType, UserRef, Workflow,
} from './api.models';

const enc = encodeURIComponent;

/** Accès au moteur de fiches (contrat M1). */
@Injectable({ providedIn: 'root' })
export class RecordsService {
  private readonly http = inject(HttpClient);

  list(query: RecordQuery): Promise<Page<RecordSummary>> {
    let params = new HttpParams();
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') {
        params = params.set(k, String(v));
      }
    }
    return firstValueFrom(this.http.get<Page<RecordSummary>>('/api/records', { params }));
  }

  get(id: string): Promise<QmsRecord> {
    return firstValueFrom(this.http.get<QmsRecord>(`/api/records/${enc(id)}`));
  }

  create(body: NewRecord): Promise<QmsRecord> {
    return firstValueFrom(this.http.post<QmsRecord>('/api/records', body));
  }

  patch(id: string, body: RecordPatch): Promise<QmsRecord> {
    return firstValueFrom(this.http.patch<QmsRecord>(`/api/records/${enc(id)}`, body));
  }

  transition(id: string, code: string, comment: string): Promise<QmsRecord> {
    return firstValueFrom(this.http.post<QmsRecord>(`/api/records/${enc(id)}/transitions/${enc(code)}`, { comment }));
  }

  addAction(id: string, body: NewAction): Promise<QmsRecord> {
    return firstValueFrom(this.http.post<QmsRecord>(`/api/records/${enc(id)}/actions`, body));
  }

  history(id: string): Promise<HistoryEntry[]> {
    return firstValueFrom(this.http.get<HistoryEntry[]>(`/api/records/${enc(id)}/history`));
  }

  myTasks(): Promise<MyTask[]> {
    return firstValueFrom(this.http.get<MyTask[]>('/api/my-tasks'));
  }

  summary(): Promise<DashboardSummary> {
    return firstValueFrom(this.http.get<DashboardSummary>('/api/dashboard/summary'));
  }

  users(q: string): Promise<UserRef[]> {
    return firstValueFrom(this.http.get<UserRef[]>('/api/users', { params: { q } }));
  }

  recordTypes(): Promise<RecordType[]> {
    return firstValueFrom(this.http.get<RecordType[]>('/api/record-types'));
  }

  workflow(code: string): Promise<Workflow> {
    return firstValueFrom(this.http.get<Workflow>(`/api/record-types/${enc(code)}/workflow`));
  }

  attachments(id: string): Promise<Attachment[]> {
    return firstValueFrom(this.http.get<Attachment[]>(`/api/records/${enc(id)}/attachments`));
  }

  /** Envoi multipart (`file`) avec `Idempotency-Key` : un renvoi ne crée pas de doublon. */
  addAttachment(id: string, file: File, key: string = newIdempotencyKey()): Promise<Attachment> {
    const form = new FormData();
    form.append('file', file, file.name);
    return firstValueFrom(
      this.http.post<Attachment>(`/api/records/${enc(id)}/attachments`, form, { headers: { 'Idempotency-Key': key } }),
    );
  }

  saveWorkflow(code: string, wf: unknown): Promise<Workflow> {
    return firstValueFrom(this.http.put<Workflow>(`/api/record-types/${enc(code)}/workflow`, wf));
  }
}

/** URL de téléchargement d'une pièce jointe (même origine : cookie de session BFF). */
export function attachmentUrl(id: string): string {
  return `/api/attachments/${enc(id)}`;
}

export function newIdempotencyKey(): string {
  // randomUUID n'existe qu'en contexte sécurisé (HTTPS) : repli sur getRandomValues en HTTP sur réseau fermé.
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
