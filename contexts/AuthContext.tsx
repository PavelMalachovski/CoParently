import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';

interface User {
  name: string;
  email: string;
  picture: string;
}

interface AuthContextType {
  user: User | null;
  isLoggedIn: boolean;
  login: () => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    try {
      const storedUser = localStorage.getItem('coParentlyUser');
      if (storedUser) {
        setUser(JSON.parse(storedUser));
      }
    } catch (error) {
      console.error("Failed to parse user from localStorage", error);
      localStorage.removeItem('coParentlyUser');
    }
    setLoading(false);
  }, []);

  const login = () => {
    // Simulate Google OAuth login by creating a mock user
    const mockUser: User = {
      name: 'Alex Doe',
      email: 'alex.doe@example.com',
      picture: `https://i.pravatar.cc/150?u=alexdoe`,
    };
    try {
      localStorage.setItem('coParentlyUser', JSON.stringify(mockUser));
      setUser(mockUser);
    } catch (error) {
       console.error("Failed to save user to localStorage", error);
    }
  };

  const logout = () => {
    try {
      localStorage.removeItem('coParentlyUser');
    } catch (error) {
        console.error("Failed to remove user from localStorage", error);
    }
    setUser(null);
  };

  const value = {
    user,
    isLoggedIn: !!user,
    login,
    logout,
  };

  // Avoid rendering children until the initial auth state is determined
  return (
    <AuthContext.Provider value={value}>
      {!loading && children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
