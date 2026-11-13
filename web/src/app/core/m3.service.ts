import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Page } from './api.models';
import {
  AckStatus, Answer, AuditDetail, AuditQuestion, AuditSummary, DocVersion, DocumentSummary, DocumentView, ImportReport, Requirement,
  RequirementDetail, RequirementRef, ServerAcks, Standard, itemsOf, toAcks, toQuestion, toVersion,
} from './m3.models';

/** Corps de `POST /api/documents` (CreateDocument côté serveur). */
export interface NewDocument {
  code: string;
  title: string;
  docType: string;
  processus: string;
  siteId: string | null;
  recipients: { roles: string[]; siteIds: string[] };
  verifierRoles: string[];
  approverRoles: string[];
  reviewDate: string | null;
}
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

  async linked(recordId: string): Promise<RequirementRef[]> {
    return itemsOf(await firstValueFrom(this.http.get<List<RequirementRef>>(`/api/records/${enc(recordId)}/requirements`)));
  }

  async requirements(standardId: string): Promise<Requirement[]> {
    return itemsOf(await firstValueFrom(this.http.get<List<Requirement>>(`/api/standards/${enc(standardId)}/requirements`)));
  }

  async requirement(id: string): Promise<RequirementDetail> {
    const d = await firstValueFrom(this.http.get<RequirementDetail>(`/api/requirements/${enc(id)}`));
    return { ...d, standardCode: d.standardCode ?? d.standard?.code ?? null };
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
    const list = itemsOf(await firstValueFrom(this.http.get<List<AuditSummary>>('/api/audits', { params })));
    return list.map((a) => ({ ...a, plannedDate: a.plannedDate ?? a.datePrevue ?? null }));
  }

  /** Grille de l'audit, lue dans `GET /api/audits/{id}` (`questions`). */
  async checklist(auditId: string): Promise<AuditQuestion[]> {
    const a = await firstValueFrom(this.http.get<AuditDetail>(`/api/audits/${enc(auditId)}`));
    return (a.questions ?? a.checklist ?? []).map(toQuestion);
  }

  async generate(auditId: string, standardIds: string[], requirementIds?: string[]): Promise<AuditQuestion[]> {
    const body = requirementIds?.length ? { standardIds, requirementIds } : { standardIds };
    return itemsOf(
      await firstValueFrom(this.http.post<List<AuditQuestion>>(`/api/audits/${enc(auditId)}/checklist/generate`, body)),
    ).map(toQuestion);
  }

  async editQuestion(auditId: string, questionId: string, text: string): Promise<AuditQuestion> {
    return toQuestion(await firstValueFrom(
      this.http.patch<AuditQuestion>(`/api/audits/${enc(auditId)}/questions/${enc(questionId)}`, { text }),
    ));
  }

  deleteQuestion(auditId: string, questionId: string): Promise<unknown> {
    return firstValueFrom(this.http.delete(`/api/audits/${enc(auditId)}/questions/${enc(questionId)}`));
  }

  async answer(auditId: string, questionId: string, a: Answer, key: string = newIdempotencyKey()): Promise<AuditQuestion> {
    return toQuestion(await firstValueFrom(
      this.http.put<AuditQuestion>(`/api/audits/${enc(auditId)}/answers/${enc(questionId)}`, a, {
        headers: { 'Idempotency-Key': key },
      }),
    ));
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
    const list = itemsOf(await firstValueFrom(this.http.get<List<DocumentSummary>>('/api/documents', { params })));
    return list.map((d) => ({
      ...d,
      data: { code: d.code, docType: d.docType, processus: d.processus, reviewDate: d.reviewDate, ...(d.data ?? {}) },
    }));
  }

  /** `POST /api/documents` (CreateDocument) → DocumentView ; renvoie l'id du document. */
  async create(body: NewDocument): Promise<{ id: string }> {
    const v = await firstValueFrom(this.http.post<DocumentView>('/api/documents', body));
    return { id: v.document.id };
  }

  versionTransition(docId: string, n: string, code: string): Promise<unknown> {
    return firstValueFrom(this.http.post(`/api/documents/${enc(docId)}/versions/${enc(n)}/transitions/${enc(code)}`, {}));
  }

  /** Versions lues dans `GET /api/documents/{id}`, la plus récente d'abord. */
  async versions(docId: string): Promise<DocVersion[]> {
    const v = await firstValueFrom(this.http.get<DocumentView>(`/api/documents/${enc(docId)}`));
    return (v.versions ?? []).map(toVersion).sort((a, b) => Number(b.number) - Number(a.number));
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

  async acks(docId: string, version: string): Promise<AckStatus> {
    return toAcks(await firstValueFrom(this.http.get<ServerAcks>(`/api/documents/${enc(docId)}/versions/${enc(version)}/acks`)));
  }
}

export function versionFileUrl(docId: string, version: string): string {
  return `/api/documents/${enc(docId)}/versions/${enc(version)}/file`;
}
