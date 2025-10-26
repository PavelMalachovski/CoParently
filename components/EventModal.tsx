
import React from 'react';
import type { CalendarEvent, Parent } from '../types';
import { CloseIcon } from './IconComponents';

interface EventModalProps {
  isOpen: boolean;
  onClose: () => void;
  date: Date;
  event?: CalendarEvent;
  parents: Parent[];
  onSave: (date: Date, parentId: string) => void;
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
  if (!isOpen) return null;

  const handleParentSelect = (parentId: string) => {
    onSave(date, parentId);
  };

  const handleDelete = () => {
    onDelete(date);
  };
  
  const formattedDate = date.toLocaleDateString('en-US', {
    weekday: 'long',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });

  return (
    <div 
        className="fixed inset-0 bg-black bg-opacity-50 z-50 flex justify-center items-center p-4"
        onClick={onClose}
    >
      <div 
        className="bg-white rounded-2xl shadow-xl w-full max-w-md p-6 relative animate-fade-in-up"
        onClick={(e) => e.stopPropagation()}
      >
        <button onClick={onClose} className="absolute top-4 right-4 text-gray-400 hover:text-gray-600 transition">
          <CloseIcon />
        </button>
        
        <h2 className="text-xl font-bold text-gray-800 mb-2">Edit Schedule</h2>
        <p className="text-gray-500 mb-6">{formattedDate}</p>

        <div className="space-y-3">
          <p className="font-semibold text-sm text-gray-600">Assign to:</p>
          {parents.map((parent) => (
            <button
              key={parent.id}
              onClick={() => handleParentSelect(parent.id)}
              className={`w-full text-left p-4 rounded-lg font-semibold transition-all duration-200 transform hover:scale-105 ${parent.color} ${parent.textColor} ${event?.parentId === parent.id ? 'ring-2 ring-offset-2 ring-indigo-500' : ''}`}
            >
              {parent.name}
            </button>
          ))}
        </div>

        {event && (
          <div className="mt-6 pt-6 border-t border-gray-200">
            <button
              onClick={handleDelete}
              className="w-full bg-red-100 text-red-700 hover:bg-red-200 font-bold py-3 px-4 rounded-lg transition"
            >
              Remove Assignment
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
