import { Component, signal, computed, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ValidationService, ValidationResult } from '../../services/validation.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-document-upload',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './document-upload.component.html',
  styleUrl: './document-upload.component.css'
})
export class DocumentUploadComponent implements OnDestroy {
  files = signal<File[]>([]);
  results = signal<ValidationResult[]>([]);
  isLoading = signal(false);
  isDragOver = signal(false);
  errorMessage = signal<string | null>(null);
  currentLoadingMsg = signal('Iniciando procesamiento...');

  hasFiles = computed(() => this.files().length > 0);
  hasResults = computed(() => this.results().length > 0);
  processedCount = computed(() => this.results().length);
  totalCount = computed(() => this.files().length);
  progressPercent = computed(() =>
    this.totalCount() > 0 ? (this.processedCount() / this.totalCount()) * 100 : 0
  );
  allDone = computed(() =>
    !this.isLoading() && this.processedCount() === this.totalCount() && this.totalCount() > 0
  );

  private subscription?: Subscription;
  private msgInterval?: ReturnType<typeof setInterval>;

  private readonly loadingMessages = [
    'Extrayendo texto del PDF...',
    'Analizando el contenido del documento...',
    'Consultando la Superintendencia de Notariado y Registro...',
    'Verificando autenticidad del PIN...',
    'Procesando siguiente documento...',
    'Los PDFs escaneados pueden tardar un poco más...',
    'Aplicando reconocimiento óptico de caracteres (OCR)...',
  ];

  constructor(private validationService: ValidationService) {}

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    if (this.msgInterval) clearInterval(this.msgInterval);
  }

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
    if (droppedFiles) this.addFiles(droppedFiles);
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
    this.subscription?.unsubscribe();
    if (this.msgInterval) clearInterval(this.msgInterval);
    this.files.set([]);
    this.results.set([]);
    this.errorMessage.set(null);
    this.isLoading.set(false);
  }

  validateDocuments(): void {
    if (this.files().length === 0) return;

    this.isLoading.set(true);
    this.results.set([]);
    this.errorMessage.set(null);
    this.currentLoadingMsg.set(this.loadingMessages[0]);

    let msgIndex = 0;
    this.msgInterval = setInterval(() => {
      msgIndex = (msgIndex + 1) % this.loadingMessages.length;
      this.currentLoadingMsg.set(this.loadingMessages[msgIndex]);
    }, 3500);

    this.subscription = this.validationService.validateDocuments(this.files()).subscribe({
      next: (result) => {
        this.results.update(current => [...current, result]);
      },
      error: (err) => {
        this.errorMessage.set('Error al conectar con el servidor. Verifica que el backend esté ejecutándose.');
        this.isLoading.set(false);
        if (this.msgInterval) clearInterval(this.msgInterval);
        console.error('Error de validación:', err);
      },
      complete: () => {
        this.isLoading.set(false);
        if (this.msgInterval) clearInterval(this.msgInterval);
      }
    });
  }

  getFileResult(fileName: string): ValidationResult | undefined {
    return this.results().find(r => r.fileName === fileName);
  }

  getFileState(fileName: string): 'pending' | 'processing' | 'done' {
    if (this.getFileResult(fileName)) return 'done';
    if (!this.isLoading()) return 'pending';
    // Backend processes up to 4 files concurrently, mark those as "processing"
    const pendingFiles = this.files().filter(f => !this.getFileResult(f.name));
    const processingSet = new Set(pendingFiles.slice(0, 4).map(f => f.name));
    return processingSet.has(fileName) ? 'processing' : 'pending';
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'VALID': return 'status-valid';
      case 'INVALID': return 'status-invalid';
      case 'ERROR': return 'status-error';
      case 'ALERT': return 'status-alert';
      default: return '';
    }
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'VALID': return '✓';
      case 'INVALID': return '✗';
      case 'ERROR': return '⚠';
      case 'ALERT': return '⚑';
      default: return '?';
    }
  }

  getStatusLabel(status: string): string {
    switch (status) {
      case 'VALID': return 'Válido';
      case 'INVALID': return 'Inválido';
      case 'ERROR': return 'Error';
      case 'ALERT': return 'Alerta';
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
