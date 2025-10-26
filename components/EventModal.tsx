import React, { useEffect, useRef, useCallback } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { format } from 'date-fns';
import type { CalendarEvent, Parent } from '../types';
import { CloseIcon } from './IconComponents';

const eventFormSchema = z.object({
  parentId: z.string().min(1, 'Please select a parent'),
  note: z.string().max(200, 'Note must be 200 characters or less').optional(),
});

type EventFormData = z.infer<typeof eventFormSchema>;

interface EventModalProps {
  isOpen: boolean;
  onClose: () => void;
  date: Date;
  event?: CalendarEvent;
  parents: Parent[];
  onSave: (date: Date, parentId: string, note?: string) => void;
  onDelete: (date: Date) => void;
}

export const EventModal: React.FC<EventModalProps> = ({
  isOpen,
  onClose,
  date,
  event,
  parents,
  onSave,
  onDelete,
}) => {
  const modalRef = useRef<HTMLDivElement>(null);
  const firstButtonRef = useRef<HTMLButtonElement>(null);

  const {
    register,
    handleSubmit,
    formState: { errors },
    setValue,
    watch,
    reset,
  } = useForm<EventFormData>({
    resolver: zodResolver(eventFormSchema),
    defaultValues: {
      parentId: event?.parentId || '',
      note: event?.note || '',
    },
  });

  // Reset form when event changes
  useEffect(() => {
    if (isOpen) {
      reset({
        parentId: event?.parentId || '',
        note: event?.note || '',
      });
    }
  }, [isOpen, event, reset]);

  const selectedParentId = watch('parentId');

  const onSubmit = (data: EventFormData) => {
    onSave(date, data.parentId, data.note);
  };

  // Handle ESC key press
  useEffect(() => {
    const handleEsc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };

    if (isOpen) {
      document.addEventListener('keydown', handleEsc);
      // Focus the first button when modal opens
      firstButtonRef.current?.focus();
      // Prevent body scroll when modal is open
      document.body.style.overflow = 'hidden';
    }

    return () => {
      document.removeEventListener('keydown', handleEsc);
      document.body.style.overflow = 'unset';
    };
  }, [isOpen, onClose]);

  // Trap focus within modal
  useEffect(() => {
    if (!isOpen || !modalRef.current) return;

    const modal = modalRef.current;
    const focusableElements = modal.querySelectorAll(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
    );
    const firstElement = focusableElements[0] as HTMLElement;
    const lastElement = focusableElements[focusableElements.length - 1] as HTMLElement;

    const handleTab = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return;

      if (e.shiftKey) {
        if (document.activeElement === firstElement) {
          e.preventDefault();
          lastElement?.focus();
        }
      } else {
        if (document.activeElement === lastElement) {
          e.preventDefault();
          firstElement?.focus();
        }
      }
    };

    modal.addEventListener('keydown', handleTab as any);
    return () => modal.removeEventListener('keydown', handleTab as any);
  }, [isOpen]);

  if (!isOpen) return null;

  const handleParentSelect = (parentId: string) => {
    setValue('parentId', parentId);
  };

  const handleDelete = () => {
    onDelete(date);
  };

  const formattedDate = format(date, 'EEEE, MMMM d, yyyy');

  return (
    <div
        className="fixed inset-0 bg-black bg-opacity-50 z-50 flex justify-center items-center p-4"
        onClick={onClose}
        role="presentation"
    >
      <div
        ref={modalRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
        aria-describedby="modal-description"
        className="bg-white rounded-2xl shadow-xl w-full max-w-md p-6 relative animate-fade-in-up"
        onClick={(e) => e.stopPropagation()}
      >
        <button
          onClick={onClose}
          className="absolute top-4 right-4 text-gray-400 hover:text-gray-600 transition"
          aria-label="Close modal"
        >
          <CloseIcon />
        </button>

        <h2 id="modal-title" className="text-xl font-bold text-gray-800 mb-2">Edit Schedule</h2>
        <p id="modal-description" className="text-gray-500 mb-6">{formattedDate}</p>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="space-y-3">
            <label className="font-semibold text-sm text-gray-600">
              Assign to: {errors.parentId && <span className="text-red-500 ml-2">{errors.parentId.message}</span>}
            </label>
            {parents.map((parent, index) => (
              <button
                key={parent.id}
                type="button"
                ref={index === 0 ? firstButtonRef : null}
                onClick={() => handleParentSelect(parent.id)}
                className={`w-full text-left p-4 rounded-lg font-semibold transition-all duration-200 transform hover:scale-105 ${parent.color} ${parent.textColor} ${selectedParentId === parent.id ? 'ring-2 ring-offset-2 ring-indigo-500' : ''}`}
                aria-pressed={selectedParentId === parent.id}
              >
                {parent.name}
              </button>
            ))}
          </div>

          <div>
            <label htmlFor="note" className="block font-semibold text-sm text-gray-600 mb-2">
              Note (optional):
            </label>
            <textarea
              id="note"
              {...register('note')}
              className="w-full p-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-transparent resize-none"
              rows={3}
              placeholder="Add a note about this day..."
            />
            {errors.note && (
              <p className="text-red-500 text-sm mt-1">{errors.note.message}</p>
            )}
          </div>

          <button
            type="submit"
            className="w-full bg-indigo-600 text-white hover:bg-indigo-700 font-bold py-3 px-4 rounded-lg transition"
          >
            Save Schedule
          </button>

          {event && (
            <button
              type="button"
              onClick={handleDelete}
              className="w-full bg-red-100 text-red-700 hover:bg-red-200 font-bold py-3 px-4 rounded-lg transition"
              aria-label="Remove schedule assignment"
            >
              Remove Assignment
            </button>
          )}
        </form>
      </div>
    </div>
  );
};
