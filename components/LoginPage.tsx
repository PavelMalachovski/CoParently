
import React from 'react';
import { useAuth } from '../contexts/AuthContext';
import { GoogleIcon } from './IconComponents';

export const LoginPage: React.FC = () => {
  const { login } = useAuth();

  return (
    <div className="flex items-center justify-center min-h-screen bg-gray-50 p-4">
      <div className="text-center p-8 bg-white shadow-lg rounded-2xl max-w-sm w-full animate-fade-in-up">
        <div className="flex justify-center mb-8">
          <img src="https://i.ibb.co/6wmTN1v/Co-Parently.png" alt="CoParently Logo" className="w-48" />
        </div>
        <button
          onClick={login}
          className="w-full flex items-center justify-center gap-3 bg-white border border-gray-300 text-gray-700 font-semibold py-3 px-4 rounded-lg hover:bg-gray-100 transition duration-200 transform hover:scale-105"
        >
          <GoogleIcon />
          Sign in with Google
        </button>
      </div>
    </div>
  );
};