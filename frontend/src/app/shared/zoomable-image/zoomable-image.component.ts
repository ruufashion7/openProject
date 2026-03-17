import {
  Component,
  Input,
  ElementRef,
  ViewChild,
  HostListener,
  OnDestroy,
  AfterViewInit,
} from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-zoomable-image',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './zoomable-image.component.html',
  styleUrl: './zoomable-image.component.css',
})
export class ZoomableImageComponent implements AfterViewInit, OnDestroy {
  @Input() src = '';
  @Input() alt = '';
  @Input() title = '';
  @Input() minZoom = 0.25;
  @Input() maxZoom = 5;
  @Input() zoomStep = 0.25;

  Math = Math;

  @ViewChild('imageContainer') imageContainer!: ElementRef<HTMLDivElement>;
  @ViewChild('image') imageEl!: ElementRef<HTMLImageElement>;

  scale = 1;
  translateX = 0;
  translateY = 0;
  isDragging = false;
  isFullscreen = false;
  private startX = 0;
  private startY = 0;
  private startTranslateX = 0;
  private startTranslateY = 0;
  private fullscreenEl: HTMLElement | null = null;

  private get container(): HTMLDivElement | null {
    return this.imageContainer?.nativeElement ?? null;
  }

  ngAfterViewInit(): void {
    this.centerImage();
    const el = this.container;
    if (el) {
      el.addEventListener('wheel', this.wheelHandler, { passive: false });
      document.addEventListener('fullscreenchange', this.fullscreenChangeHandler);
    }
  }

  ngOnDestroy(): void {
    const el = this.container;
    if (el) {
      el.removeEventListener('wheel', this.wheelHandler);
    }
    document.removeEventListener('fullscreenchange', this.fullscreenChangeHandler);
    this.exitFullscreen();
  }

  private wheelHandler = (e: WheelEvent): void => this.onWheel(e);
  private fullscreenChangeHandler = (): void => {
    this.isFullscreen = !!document.fullscreenElement;
  };

  private centerImage(): void {
    if (!this.container) return;
    this.scale = 1;
    this.translateX = 0;
    this.translateY = 0;
  }

  zoomIn(): void {
    this.setScale(Math.min(this.maxZoom, this.scale + this.zoomStep));
  }

  zoomOut(): void {
    this.setScale(Math.max(this.minZoom, this.scale - this.zoomStep));
  }

  reset(): void {
    this.scale = 1;
    this.translateX = 0;
    this.translateY = 0;
  }

  private setScale(s: number): void {
    this.scale = Math.max(this.minZoom, Math.min(this.maxZoom, s));
  }

  onWheel(event: WheelEvent): void {
    event.preventDefault();
    const delta = event.deltaY > 0 ? -this.zoomStep : this.zoomStep;
    this.setScale(this.scale + delta);
  }

  onMouseDown(event: MouseEvent): void {
    if (event.button !== 0) return;
    this.isDragging = true;
    this.startX = event.clientX;
    this.startY = event.clientY;
    this.startTranslateX = this.translateX;
    this.startTranslateY = this.translateY;
  }

  @HostListener('document:mouseup')
  onMouseUp(): void {
    this.isDragging = false;
  }

  @HostListener('document:mousemove', ['$event'])
  onMouseMove(event: MouseEvent): void {
    if (!this.isDragging) return;
    this.translateX = this.startTranslateX + event.clientX - this.startX;
    this.translateY = this.startTranslateY + event.clientY - this.startY;
  }

  toggleFullscreen(): void {
    if (this.isFullscreen) {
      this.exitFullscreen();
    } else {
      this.enterFullscreen();
    }
  }

  private enterFullscreen(): void {
    const el = this.container;
    if (!el) return;
    this.fullscreenEl = el;
    el.requestFullscreen?.()?.catch(() => {});
    this.isFullscreen = true;
  }

  private exitFullscreen(): void {
    if (document.fullscreenElement) {
      document.exitFullscreen?.()?.catch(() => {});
    }
    this.isFullscreen = false;
    this.fullscreenEl = null;
  }

  get transformStyle(): string {
    return `translate(${this.translateX}px, ${this.translateY}px) scale(${this.scale})`;
  }
}
