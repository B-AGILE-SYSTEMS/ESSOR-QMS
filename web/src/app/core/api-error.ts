import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from './api.models';

/** Message français lisible pour une erreur d'API (`{error, message, details}`). */
export function errorMessage(e: unknown, fallback = 'Une erreur est survenue. Réessayez.'): string {
  if (e instanceof HttpErrorResponse) {
    const body = e.error as Partial<ApiError> | null;
    if (body && typeof body === 'object' && typeof body.message === 'string' && body.message) {
      return body.message;
    }
    if (e.status === 0) {
      return 'Serveur injoignable. Vérifiez la connexion.';
    }
    if (e.status === 403) {
      return "Vous n'avez pas les droits pour cette opération.";
    }
  }
  return fallback;
}
