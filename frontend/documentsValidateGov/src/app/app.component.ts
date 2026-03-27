import { Component } from '@angular/core';
import { DocumentUploadComponent } from './components/document-upload/document-upload.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [DocumentUploadComponent],
  template: '<app-document-upload />',
  styles: [`:host { display: block; min-height: 100vh; }`]
})
export class AppComponent {}
