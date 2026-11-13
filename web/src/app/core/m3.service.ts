import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Page } from './api.models';
import {
  AckStatus, Answer, AuditDetail, AuditQuestion, AuditSummary, DocVersion, DocumentSummary, ImportReport, Requirement, RequirementDetail, Standard, itemsOf,
} from './m3.models';
import { newIdempotencyKey } from './records.service';

const enc = encodeURIComponent;
type List<T> = T[] | Page<T>;

/** Référentiels (contrat M3). */
@Injectable({ providedIn: 'root' })
export class StandardsService {
  private readonly http = inject(HttpClient);

  async list(): Promise<Standard[]> {
    return itemsOf(await firstValueFrom(this.http.get<List<Standard>>('/api/standards')));
  }

  async requirements(standardId: string): Promise<Requirement[]> {
    return itemsOf(await firstValueFrom(this.http.get<List<Requirement>>(`/api/standards/${enc(standardId)}/requirements`)));
  }

  requirement(id: string): Promise<RequirementDetail> {
    return firstValueFrom(this.http.get<RequirementDetail>(`/api/requirements/${enc(id)}`));
  }

  /** Import xlsx/csv (champ `file`) + `standardCode`, `standardTitle`, `standardVersion`. Tout ou rien. */
  import(file: File, std: { code: string; title: string; version: string }, dryRun: boolean): Promise<ImportReport> {
    const form = new FormData();
    form.append('file', file, file.name);
    form.append('standardCode', std.code);
    form.append('standardTitle', std.title);
    form.append('standardVersion', std.version);
    return firstValueFrom(
      this.http.post<ImportReport>('/api/standards/import', form, { params: dryRun ? { dryRun: 'true' } : {} }),
    );
  }

  linkRequirements(recordId: string, ids: string[]): Promise<unknown> {
    return firstValueFrom(this.http.put(`/api/records/${enc(recordId)}/requirements`, ids));
  }
}

export const IMPORT_TEMPLATE_URL = '/api/standards/import/template';

/** Audits (contrat M3). */
@Injectable({ providedIn: 'root' })
export class AuditsService {
  private readonly http = inject(HttpClient);

  /** Programme : `year`, `status` (liste séparée par virgules), `auditor=me`. */
  async program(year: number, opts: { status?: string[]; mine?: boolean } = {}): Promise<AuditSummary[]> {
    const params: Record<string, string> = { year: String(year) };
    if (opts.status?.length) params['status'] = opts.status.join(',');
    if (opts.mine) params['auditor'] = 'me';
    return itemsOf(await firstValueFrom(this.http.get<List<AuditSummary>>('/api/audits', { params })));
  }

  /** Grille de l'audit, lue dans `GET /api/audits/{id}` (`questions`). */
  async checklist(auditId: string): Promise<AuditQuestion[]> {
    const a = await firstValueFrom(this.http.get<AuditDetail>(`/api/audits/${enc(auditId)}`));
    return a.questions ?? a.checklist ?? [];
  }

  async generate(auditId: string, standardIds: string[], requirementIds?: string[]): Promise<AuditQuestion[]> {
    const body = requirementIds?.length ? { standardIds, requirementIds } : { standardIds };
    return itemsOf(
      await firstValueFrom(this.http.post<List<AuditQuestion>>(`/api/audits/${enc(auditId)}/checklist/generate`, body)),
    );
  }

  editQuestion(auditId: string, questionId: string, question: string): Promise<AuditQuestion> {
    return firstValueFrom(
      this.http.patch<AuditQuestion>(`/api/audits/${enc(auditId)}/questions/${enc(questionId)}`, { question }),
    );
  }

  deleteQuestion(auditId: string, questionId: string): Promise<unknown> {
    return firstValueFrom(this.http.delete(`/api/audits/${enc(auditId)}/questions/${enc(questionId)}`));
  }

  answer(auditId: string, questionId: string, a: Answer, key: string = newIdempotencyKey()): Promise<AuditQuestion> {
    return firstValueFrom(
      this.http.put<AuditQuestion>(`/api/audits/${enc(auditId)}/answers/${enc(questionId)}`, a, {
        headers: { 'Idempotency-Key': key },
      }),
    );
  }
}

export function auditReportUrl(auditId: string): string {
  return `/api/audits/${enc(auditId)}/report.pdf`;
}

/** Gestion documentaire (contrat M3). */
@Injectable({ providedIn: 'root' })
export class DocumentsService {
  private readonly http = inject(HttpClient);

  async list(f: { status?: string; type?: string; q?: string }): Promise<DocumentSummary[]> {
    const params: Record<string, string> = {};
    for (const [k, v] of Object.entries(f)) if (v) params[k] = v;
    return itemsOf(await firstValueFrom(this.http.get<List<DocumentSummary>>('/api/documents', { params })));
  }

  create(body: { siteId: string; title: string; description: string; data: Record<string, unknown> }): Promise<{ id: string }> {
    return firstValueFrom(this.http.post<{ id: string }>('/api/documents', body));
  }

  versionTransition(docId: string, n: string, code: string): Promise<unknown> {
    return firstValueFrom(this.http.post(`/api/documents/${enc(docId)}/versions/${enc(n)}/transitions/${enc(code)}`, {}));
  }

  async versions(docId: string): Promise<DocVersion[]> {
    return itemsOf(await firstValueFrom(this.http.get<List<DocVersion>>(`/api/documents/${enc(docId)}/versions`)));
  }

  addVersion(docId: string, file: File, note: string): Promise<DocVersion> {
    const form = new FormData();
    form.append('file', file, file.name);
    form.append('note', note);
    return firstValueFrom(this.http.post<DocVersion>(`/api/documents/${enc(docId)}/versions`, form));
  }

  ack(docId: string, version: string): Promise<unknown> {
    return firstValueFrom(this.http.post(`/api/documents/${enc(docId)}/versions/${enc(version)}/ack`, {}));
  }

  acks(docId: string, version: string): Promise<AckStatus> {
    return firstValueFrom(this.http.get<AckStatus>(`/api/documents/${enc(docId)}/versions/${enc(version)}/acks`));
  }
}

export function versionFileUrl(docId: string, version: string): string {
  return `/api/documents/${enc(docId)}/versions/${enc(version)}/file`;
}
