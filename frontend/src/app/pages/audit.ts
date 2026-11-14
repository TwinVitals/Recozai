import { Component, DestroyRef, inject, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, forkJoin } from 'rxjs';
import { Api, AuditEvent, Page, Settings, errorKey } from '../core/api.service';
import { Language } from '../core/language.service';

@Component({
  imports: [TranslatePipe],
  template: ` <section class="card">
    <h1>{{ 'audit' | translate }}</h1>
    @if (loading()) {
      <p role="status">{{ 'loading' | translate }}</p>
    }
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{{ 'action' | translate }}</th>
            <th>{{ 'when' | translate }}</th>
            <th>{{ 'actor' | translate }}</th>
          </tr>
        </thead>
        <tbody>
          @for (event of events(); track event.id) {
            <tr>
              <td>{{ 'events.' + event.action | translate }}</td>
              <td>{{ date(event.occurredAt) }}</td>
              <td>{{ event.actorId }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    @if (!loading() && !events().length && !error()) {
      <p>{{ 'empty' | translate }}</p>
    }
    <div class="actions">
      <button [disabled]="page() === 0 || loading()" (click)="load(page() - 1)">
        {{ 'previous' | translate }}
      </button>
      <button [disabled]="events().length < 25 || loading()" (click)="load(page() + 1)">
        {{ 'next' | translate }}
      </button>
    </div>
    @if (error()) {
      <p class="error" role="alert">{{ error() | translate }}</p>
    }
  </section>`,
})
export class AuditPage {
  readonly events = signal<AuditEvent[]>([]);
  readonly page = signal(0);
  readonly loading = signal(false);
  readonly error = signal('');
  private readonly api = inject(Api);
  private readonly language = inject(Language);
  private readonly destroy = inject(DestroyRef);
  private readonly zone = signal('Africa/Dakar');
  constructor() {
    this.load(0);
  }
  date(value: string) {
    return new Intl.DateTimeFormat(this.language.translate.getCurrentLang() ?? 'fr-SN', {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: this.zone(),
    }).format(new Date(value));
  }
  load(page: number) {
    this.loading.set(true);
    this.error.set('');
    forkJoin({
      settings: this.api.get<Settings>('/clinic-settings'),
      events: this.api.get<Page<AuditEvent>>(`/audit-events?page=${page}`),
    })
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe({
        next: (result) => {
          this.zone.set(result.settings.timezone);
          this.events.set(result.events.items);
          this.page.set(page);
        },
        error: (error) => this.error.set(errorKey(error)),
      });
  }
}
