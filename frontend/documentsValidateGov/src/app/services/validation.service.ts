import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ValidationResult {
  fileName: string;
  pin: string;
  status: 'VALID' | 'INVALID' | 'ERROR';
  message: string;
}

@Injectable({
  providedIn: 'root'
})
export class ValidationService {

  private apiUrl = 'http://localhost:8080/api/validate-documents';

  constructor(private http: HttpClient) {}

  validateDocuments(files: File[]): Observable<ValidationResult[]> {
    const formData = new FormData();
    files.forEach(file => formData.append('files', file));
    return this.http.post<ValidationResult[]>(this.apiUrl, formData);
  }
}
