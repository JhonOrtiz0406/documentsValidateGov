import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface ValidationResult {
  fileName: string;
  pin: string;
  status: 'VALID' | 'INVALID' | 'ERROR' | 'ALERT';
  message: string;
}

@Injectable({
  providedIn: 'root'
})
export class ValidationService {

  private apiUrl = environment.apiUrl;

  validateDocuments(files: File[]): Observable<ValidationResult> {
    return new Observable(observer => {
      const formData = new FormData();
      files.forEach(file => formData.append('files', file));

      const controller = new AbortController();

      fetch(this.apiUrl, {
        method: 'POST',
        body: formData,
        signal: controller.signal
      })
        .then(response => {
          if (!response.ok) {
            throw new Error(`Error del servidor: ${response.status}`);
          }
          const reader = response.body!.getReader();
          const decoder = new TextDecoder();
          let buffer = '';

          const pump = (): Promise<void> =>
            reader.read().then(({ done, value }) => {
              if (done) {
                if (buffer.trim()) {
                  try { observer.next(JSON.parse(buffer)); } catch {}
                }
                observer.complete();
                return;
              }

              buffer += decoder.decode(value, { stream: true });
              const lines = buffer.split('\n');
              buffer = lines.pop()!;

              for (const line of lines) {
                const trimmed = line.trim();
                if (trimmed) {
                  try {
                    observer.next(JSON.parse(trimmed));
                  } catch (e) {
                    console.error('Error al parsear respuesta:', e, 'Línea:', trimmed);
                  }
                }
              }

              return pump();
            });

          return pump();
        })
        .catch(err => {
          if (err.name !== 'AbortError') {
            observer.error(err);
          }
        });

      return () => controller.abort();
    });
  }
}
