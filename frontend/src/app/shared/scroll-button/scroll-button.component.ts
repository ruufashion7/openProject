import { Component, HostListener, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-scroll-button',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './scroll-button.component.html',
  styleUrl: './scroll-button.component.css'
})
export class ScrollButtonComponent implements OnInit, OnDestroy {
  showButton = false;
  isAtTop = true;
  isAtBottom = false;
  private scrollThreshold = 300; // Show button after scrolling 300px

  ngOnInit(): void {
    this.checkScrollPosition();
  }

  ngOnDestroy(): void {
    // Cleanup if needed
  }

  @HostListener('window:scroll', [])
  onWindowScroll(): void {
    this.checkScrollPosition();
  }

  private checkScrollPosition(): void {
    const windowHeight = window.innerHeight;
    const documentHeight = document.documentElement.scrollHeight;
    const scrollTop = window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0;
    const scrollBottom = scrollTop + windowHeight;

    this.isAtTop = scrollTop < 100;
    // Check if we're near the bottom (within 100px)
    this.isAtBottom = scrollBottom >= documentHeight - 100;
    
    // Show button if scrolled past threshold (but not at the very top)
    this.showButton = scrollTop > this.scrollThreshold;
  }

  scrollToTop(): void {
    window.scrollTo({
      top: 0,
      behavior: 'smooth'
    });
  }

  scrollToBottom(): void {
    window.scrollTo({
      top: document.documentElement.scrollHeight,
      behavior: 'smooth'
    });
  }

  handleClick(): void {
    if (this.isAtBottom) {
      this.scrollToTop();
    } else {
      this.scrollToBottom();
    }
  }

  get buttonIcon(): string {
    return this.isAtBottom ? '↑' : '↓';
  }

  get buttonTitle(): string {
    return this.isAtBottom ? 'Scroll to top' : 'Scroll to bottom';
  }
}

