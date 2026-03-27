import { Component, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ValidationService, ValidationResult } from '../../services/validation.service';

@Component({
  selector: 'app-document-upload',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './document-upload.component.html',
  styleUrl: './document-upload.component.css'
})
export class DocumentUploadComponent {
  files = signal<File[]>([]);
  results = signal<ValidationResult[]>([]);
  isLoading = signal(false);
  isDragOver = signal(false);
  errorMessage = signal<string | null>(null);

  hasFiles = computed(() => this.files().length > 0);
  hasResults = computed(() => this.results().length > 0);

  constructor(private validationService: ValidationService) {}

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver.set(false);

    const droppedFiles = event.dataTransfer?.files;
    if (droppedFiles) {
      this.addFiles(droppedFiles);
    }
  }

  onFileSelect(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files) {
      this.addFiles(input.files);
      input.value = '';
    }
  }

  private addFiles(fileList: FileList): void {
    const pdfFiles = Array.from(fileList).filter(f => f.type === 'application/pdf');
    if (pdfFiles.length === 0) {
      this.errorMessage.set('Solo se permiten archivos PDF');
      setTimeout(() => this.errorMessage.set(null), 3000);
      return;
    }
    this.files.update(current => [...current, ...pdfFiles]);
    this.errorMessage.set(null);
  }

  removeFile(index: number): void {
    this.files.update(current => current.filter((_, i) => i !== index));
  }

  clearAll(): void {
    this.files.set([]);
    this.results.set([]);
    this.errorMessage.set(null);
  }

  validateDocuments(): void {
    if (this.files().length === 0) return;

    this.isLoading.set(true);
    this.results.set([]);
    this.errorMessage.set(null);

    this.validationService.validateDocuments(this.files()).subscribe({
      next: (results) => {
        this.results.set(results);
        this.isLoading.set(false);
      },
      error: (err) => {
        this.errorMessage.set('Error al conectar con el servidor. Verifica que el backend esté ejecutándose.');
        this.isLoading.set(false);
        console.error('Validation error:', err);
      }
    });
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'VALID': return 'status-valid';
      case 'INVALID': return 'status-invalid';
      case 'ERROR': return 'status-error';
      default: return '';
    }
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'VALID': return '✓';
      case 'INVALID': return '✗';
      case 'ERROR': return '⚠';
      default: return '?';
    }
  }

  getStatusLabel(status: string): string {
    switch (status) {
      case 'VALID': return 'Válido';
      case 'INVALID': return 'Inválido';
      case 'ERROR': return 'Error';
      default: return status;
    }
  }

  formatFileSize(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }
}
