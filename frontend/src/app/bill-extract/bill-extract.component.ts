import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ApiService,
  BillExtractRow,
  BillExtractStatus
} from '../services/api.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { PageStateComponent } from '../shared/page-state/page-state.component';
import { messageFromHttpError } from '../shared/api-error.util';

interface PreviewItem {
  file: File;
  url: string;
}

@Component({
  selector: 'app-bill-extract',
  standalone: true,
  imports: [CommonModule, PageStateComponent],
  templateUrl: './bill-extract.component.html',
  styleUrl: './bill-extract.component.css'
})
export class BillExtractComponent implements OnInit, OnDestroy {

  status: BillExtractStatus | null = null;
  previews: PreviewItem[] = [];
  rows: BillExtractRow[] = [];
  extracting = false;
  error = '';
  imagesRead = 0;
  dragOver = false;

  constructor(
    private api: ApiService,
    private permissionService: PermissionService,
    private notificationService: NotificationService,
    private router: Router
  ) {}

  ngOnInit(): void {
    if (!this.permissionService.canAccessBillExtract()) {
      this.notificationService.showPermissionError();
      this.router.navigateByUrl('/welcome');
      return;
    }
    this.refreshStatus();
  }

  ngOnDestroy(): void {
    this.revokePreviews();
  }

  refreshStatus(): void {
    this.api.getBillExtractStatus().subscribe({
      next: (s) => (this.status = s),
      error: (err: HttpErrorResponse) => {
        this.error = messageFromHttpError(err, 'Could not load bill reader status.');
      }
    });
  }

  onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.addFiles(input.files);
    input.value = '';
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver = false;
    this.addFiles(event.dataTransfer?.files);
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragOver = true;
  }

  onDragLeave(): void {
    this.dragOver = false;
  }

  addFiles(list: FileList | null | undefined): void {
    if (!list?.length) {
      return;
    }
    const existing = new Set(this.previews.map((p) => `${p.file.name}:${p.file.size}:${p.file.lastModified}`));
    for (const file of Array.from(list)) {
      if (!file.type.startsWith('image/') && !/\.(jpe?g|png|webp|gif)$/i.test(file.name)) {
        this.notificationService.showError(`${file.name} is not an image.`);
        continue;
      }
      const key = `${file.name}:${file.size}:${file.lastModified}`;
      if (existing.has(key)) {
        continue;
      }
      existing.add(key);
      this.previews = [...this.previews, { file, url: URL.createObjectURL(file) }];
    }
    this.rows = [];
    this.imagesRead = 0;
  }

  removePreview(index: number): void {
    const item = this.previews[index];
    if (item) {
      URL.revokeObjectURL(item.url);
    }
    this.previews = this.previews.filter((_, i) => i !== index);
  }

  clearAll(): void {
    this.revokePreviews();
    this.previews = [];
    this.rows = [];
    this.imagesRead = 0;
    this.error = '';
  }

  extract(): void {
    if (!this.previews.length || this.extracting) {
      return;
    }
    if (this.status && !this.status.ready) {
      this.notificationService.showError(
        this.status.setupHint || 'Configure AI_AGENT_API_KEY on the server first.'
      );
      return;
    }
    this.extracting = true;
    this.error = '';
    this.rows = [];
    this.api.extractBills(this.previews.map((p) => p.file)).subscribe({
      next: (res) => {
        this.extracting = false;
        this.rows = res.rows ?? [];
        this.imagesRead = res.imagesRead ?? this.previews.length;
      },
      error: (err: HttpErrorResponse) => {
        this.extracting = false;
        this.error = messageFromHttpError(err, 'Could not read the bills.');
        this.notificationService.showError(this.error);
      }
    });
  }

  copyTable(): void {
    if (!this.rows.length) {
      return;
    }
    const header = this.columns().join('\t');
    const body = this.rows.map((r) => this.rowValues(r).join('\t')).join('\n');
    navigator.clipboard.writeText(`${header}\n${body}`).then(
      () => this.notificationService.showSuccess('Table copied. Paste into Excel or Sheets.'),
      () => this.notificationService.showError('Could not copy.')
    );
  }

  downloadCsv(): void {
    if (!this.rows.length) {
      return;
    }
    const header = this.columns().map((c) => this.csvCell(c)).join(',');
    const body = this.rows.map((r) => this.rowValues(r).map((v) => this.csvCell(v)).join(',')).join('\n');
    const blob = new Blob([`${header}\n${body}\n`], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `bills-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  display(value: string | null | undefined): string {
    return value == null || value.trim() === '' ? '—' : value;
  }

  private columns(): string[] {
    return [
      'Bill No',
      'Total Amount',
      'Discount',
      'Amount after discount',
      'Card/GPay',
      'Salesman',
      'Time',
      'Remark'
    ];
  }

  private rowValues(row: BillExtractRow): string[] {
    return [
      row.billNo,
      row.totalAmount,
      row.discount,
      row.amountAfterDiscount,
      row.payment,
      row.salesman,
      row.time,
      row.remark
    ];
  }

  private csvCell(value: string): string {
    const v = value ?? '';
    if (/[",\n]/.test(v)) {
      return `"${v.replace(/"/g, '""')}"`;
    }
    return v;
  }

  private revokePreviews(): void {
    for (const item of this.previews) {
      URL.revokeObjectURL(item.url);
    }
  }
}
