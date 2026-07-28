import { Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AiAgentAttachment,
  AiAgentConversation,
  AiAgentMessage,
  AiAgentStatus,
  ApiService
} from '../services/api.service';
import { PermissionService } from '../auth/permission.service';
import { NotificationService } from '../shared/notification.service';
import { messageFromHttpError } from '../shared/api-error.util';

@Component({
  selector: 'app-ai-agent',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './ai-agent.component.html',
  styleUrl: './ai-agent.component.css'
})
export class AiAgentComponent implements OnInit {
  @ViewChild('threadEnd') threadEnd?: ElementRef<HTMLDivElement>;

  status: AiAgentStatus | null = null;
  conversations: AiAgentConversation[] = [];
  messages: AiAgentMessage[] = [];
  activeConversationId: string | null = null;
  draft = '';
  sending = false;
  loadingHistory = false;
  error = '';

  constructor(
    private api: ApiService,
    private permissionService: PermissionService,
    private notificationService: NotificationService,
    private router: Router
  ) {}

  ngOnInit(): void {
    if (!this.permissionService.canAccessAiAgent()) {
      this.notificationService.showPermissionError();
      this.router.navigateByUrl('/welcome');
      return;
    }
    this.refreshStatus();
    this.refreshConversations();
  }

  refreshStatus(): void {
    this.api.getAiAgentStatus().subscribe({
      next: (s) => (this.status = s),
      error: (err: HttpErrorResponse) => {
        this.error = messageFromHttpError(err, 'Could not load agent status.');
      }
    });
  }

  refreshConversations(): void {
    this.api.listAiAgentConversations().subscribe({
      next: (list) => (this.conversations = list),
      error: (err: HttpErrorResponse) => {
        this.notificationService.showError(messageFromHttpError(err, 'Could not load chat history.'));
      }
    });
  }

  startNewChat(): void {
    this.activeConversationId = null;
    this.messages = [];
    this.error = '';
  }

  openConversation(c: AiAgentConversation): void {
    this.activeConversationId = c.id;
    this.loadingHistory = true;
    this.error = '';
    this.api.getAiAgentMessages(c.id).subscribe({
      next: (msgs) => {
        this.messages = msgs;
        this.loadingHistory = false;
        this.scrollToBottom();
      },
      error: (err: HttpErrorResponse) => {
        this.loadingHistory = false;
        this.notificationService.showError(messageFromHttpError(err, 'Could not load messages.'));
      }
    });
  }

  deleteConversation(c: AiAgentConversation, event: Event): void {
    event.stopPropagation();
    if (!confirm('Delete this conversation?')) {
      return;
    }
    this.api.deleteAiAgentConversation(c.id).subscribe({
      next: () => {
        if (this.activeConversationId === c.id) {
          this.startNewChat();
        }
        this.refreshConversations();
      },
      error: (err: HttpErrorResponse) => {
        this.notificationService.showError(messageFromHttpError(err, 'Delete failed.'));
      }
    });
  }

  useSuggestion(text: string): void {
    this.draft = text;
  }

  send(): void {
    const text = this.draft.trim();
    if (!text || this.sending) {
      return;
    }
    if (this.status && !this.status.ready) {
      this.notificationService.showError(
        this.status.setupHint || 'Configure AI_AGENT_API_KEY on the server first.'
      );
      return;
    }
    this.sending = true;
    this.error = '';
    const optimistic: AiAgentMessage = {
      id: 'local-' + Date.now(),
      conversationId: this.activeConversationId || '',
      userId: '',
      role: 'user',
      content: text,
      createdAt: new Date().toISOString()
    };
    this.messages = [...this.messages, optimistic];
    this.draft = '';
    this.scrollToBottom();

    this.api.chatAiAgent(this.activeConversationId, text).subscribe({
      next: (res) => {
        this.sending = false;
        this.activeConversationId = res.conversationId;
        this.messages = [...this.messages, res.message];
        this.refreshConversations();
        this.scrollToBottom();
      },
      error: (err: HttpErrorResponse) => {
        this.sending = false;
        this.error = messageFromHttpError(err, 'Agent request failed.');
        this.notificationService.showError(this.error);
      }
    });
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  downloadAttachment(att: AiAgentAttachment): void {
    if (!att.downloadId) {
      return;
    }
    this.api.downloadAiAgentExport(att.downloadId).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = att.filename || 'export.pdf';
        a.click();
        URL.revokeObjectURL(url);
      },
      error: (err: HttpErrorResponse) => {
        this.notificationService.showError(messageFromHttpError(err, 'PDF download failed.'));
      }
    });
  }

  private scrollToBottom(): void {
    setTimeout(() => this.threadEnd?.nativeElement?.scrollIntoView({ behavior: 'smooth' }), 50);
  }
}
