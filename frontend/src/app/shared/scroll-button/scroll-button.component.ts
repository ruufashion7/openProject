import { Component, HostListener, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-scroll-button',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './scroll-button.component.html',
  styleUrl: './scroll-button.component.css'
})
export class ScrollButtonComponent implements OnInit {
  canScrollUp = false;
  canScrollDown = false;
  private readonly scrollThreshold = 100;

  ngOnInit(): void {
    this.checkScrollPosition();
  }

  @HostListener('window:scroll', [])
  onWindowScroll(): void {
    this.checkScrollPosition();
  }

  @HostListener('window:resize', [])
  onWindowResize(): void {
    this.checkScrollPosition();
  }

  private checkScrollPosition(): void {
    const windowHeight = window.innerHeight;
    const documentHeight = document.documentElement.scrollHeight;
    const scrollTop =
      window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0;
    const scrollBottom = scrollTop + windowHeight;

    this.canScrollUp = scrollTop > this.scrollThreshold;
    this.canScrollDown = scrollBottom < documentHeight - this.scrollThreshold;
  }

  scrollToTop(): void {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  scrollToBottom(): void {
    window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'smooth' });
  }
}
