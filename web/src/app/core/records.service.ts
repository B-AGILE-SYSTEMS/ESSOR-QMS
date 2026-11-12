import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  DashboardSummary, HistoryEntry, MyTask, NewAction, NewRecord, Page, QmsRecord, RecordPatch, RecordQuery,
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

  saveWorkflow(code: string, wf: unknown): Promise<Workflow> {
    return firstValueFrom(this.http.put<Workflow>(`/api/record-types/${enc(code)}/workflow`, wf));
  }
}
