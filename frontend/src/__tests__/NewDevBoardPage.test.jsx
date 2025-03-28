import React from 'react';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '@testing-library/jest-dom';
import NewDevBoardPage from '../pages/NewDevBoardPage';
import { devBoardsApi } from '../api/devBoardsApi';
import { controllersApi } from '../api/controllersApi';

// Mock the API
jest.mock('../api/devBoardsApi', () => ({
  devBoardsApi: {
    create: jest.fn(),
  }
}));

jest.mock('../api/controllersApi', () => ({
  controllersApi: {
    getAll: jest.fn(),
    create: jest.fn(),
  }
}));

// Mock react-router-dom
jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  useNavigate: () => mockNavigate,
}));

// Mock Auth0
jest.mock('@auth0/auth0-react', () => ({
  useAuth0: () => ({
    getAccessTokenSilently: jest.fn().mockResolvedValue('fake-token'),
    isAuthenticated: true,
  }),
}));

const mockNavigate = jest.fn();

describe('NewDevBoardPage Component', () => {
  beforeEach(() => {
    // Reset all mocks
    jest.clearAllMocks();
    
    // Mock the controller data
    controllersApi.getAll.mockResolvedValue([
      { id: 1, name: 'Pro Micro', manufacturer: 'SparkFun', chipset: 'ATmega32U4' },
      { id: 2, name: 'RP2040', manufacturer: 'Raspberry Pi', chipset: 'RP2040' }
    ]);
    
    // Mock create endpoint
    devBoardsApi.create.mockResolvedValue({ id: 1, name: 'Test Dev Board' });
    controllersApi.create.mockResolvedValue({ 
      id: 3, 
      name: 'New Controller', 
      manufacturer: 'New Manufacturer', 
      chipset: 'New Chipset' 
    });
  });

  it('renders the form with all fields', async () => {
    render(
      <MemoryRouter>
        <NewDevBoardPage />
      </MemoryRouter>
    );

    // Wait for controllers to load
    await waitFor(() => {
      expect(controllersApi.getAll).toHaveBeenCalled();
    });

    // Check form elements
    expect(screen.getByText('Add New Dev Board')).toBeInTheDocument();
    expect(screen.getByLabelText(/Dev Board Name/i)).toBeInTheDocument();
    expect(screen.getByLabelText('Wireless')).toBeInTheDocument();
    
    // Check for field labels instead of trying to match by labelledby
    const labels = screen.getAllByText(/Controller|URL/i);
    expect(labels.length).toBeGreaterThanOrEqual(2);
    
    // Check buttons
    expect(screen.getByRole('button', { name: /Create Dev Board/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Cancel/i })).toBeInTheDocument();
    
    // Check for the create new controller option in the dropdown
    expect(screen.getByText('+ Create New Controller')).toBeInTheDocument();
  });

  it('submits the form with valid data', async () => {
    render(
      <MemoryRouter>
        <NewDevBoardPage />
      </MemoryRouter>
    );

    // Wait for controllers to load
    await waitFor(() => {
      expect(controllersApi.getAll).toHaveBeenCalled();
    });

    // Fill out the form
    fireEvent.change(screen.getByLabelText(/Dev Board Name/i), {
      target: { value: 'Test Dev Board' }
    });
    
    fireEvent.click(screen.getByRole('checkbox'));
    
    // Submit the form
    fireEvent.click(screen.getByRole('button', { name: /Create Dev Board/i }));

    // Check that API was called with correct data
    await waitFor(() => {
      expect(devBoardsApi.create).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Test Dev Board',
          wireless: true
        }),
        expect.any(Function)
      );
      expect(mockNavigate).toHaveBeenCalledWith('/devboards');
    });
  });

  it('handles API errors gracefully', async () => {
    // Mock API error
    devBoardsApi.create.mockRejectedValueOnce(new Error('API Error'));
    
    render(
      <MemoryRouter>
        <NewDevBoardPage />
      </MemoryRouter>
    );

    // Wait for controllers to load
    await waitFor(() => {
      expect(controllersApi.getAll).toHaveBeenCalled();
    });

    // Fill out the form
    fireEvent.change(screen.getByLabelText(/Dev Board Name/i), {
      target: { value: 'Test Dev Board' }
    });
    
    // Submit the form
    fireEvent.click(screen.getByRole('button', { name: /Create Dev Board/i }));

    // Check that error message is displayed
    await waitFor(() => {
      expect(screen.getByText(/Failed to create dev board/i)).toBeInTheDocument();
    });
  });

  it('navigates back when cancel is clicked', async () => {
    render(
      <MemoryRouter>
        <NewDevBoardPage />
      </MemoryRouter>
    );

    // Wait for controllers to load
    await waitFor(() => {
      expect(controllersApi.getAll).toHaveBeenCalled();
    });

    // Click cancel button
    fireEvent.click(screen.getByRole('button', { name: /Cancel/i }));

    // Check that navigate was called
    expect(mockNavigate).toHaveBeenCalledWith('/devboards');
  });
  
  it('allows creating a new controller', async () => {
    render(
      <MemoryRouter>
        <NewDevBoardPage />
      </MemoryRouter>
    );

    // Wait for controllers to load
    await waitFor(() => {
      expect(controllersApi.getAll).toHaveBeenCalled();
    });

    // Open the controller dropdown
    fireEvent.click(screen.getByText('Select Controller'));
    
    // Click create new controller option
    fireEvent.click(screen.getByText('+ Create New Controller'));
    
    // Modal should be visible
    await waitFor(() => {
      expect(screen.getByText('Create New Controller')).toBeInTheDocument();
    });
    
    // Fill out the controller form
    fireEvent.change(screen.getByLabelText(/Controller Name/i), {
      target: { value: 'New Controller' }
    });
    
    fireEvent.change(screen.getByLabelText(/Manufacturer/i), {
      target: { value: 'New Manufacturer' }
    });
    
    fireEvent.change(screen.getByLabelText(/Chipset/i), {
      target: { value: 'New Chipset' }
    });
    
    // Submit the new controller form
    fireEvent.click(screen.getByRole('button', { name: /Create Controller/i }));
    
    // Check that API was called with correct data
    await waitFor(() => {
      expect(controllersApi.create).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'New Controller',
          manufacturer: 'New Manufacturer',
          chipset: 'New Chipset'
        }),
        expect.any(Function)
      );
    });
    
    // Modal should be closed after successful creation
    await waitFor(() => {
      expect(screen.queryByText('Create New Controller')).not.toBeInTheDocument();
    });
  });
  
  it('handles controller creation errors gracefully', async () => {
    // Mock API error for controller creation
    controllersApi.create.mockRejectedValueOnce(new Error('Controller API Error'));
    
    render(
      <MemoryRouter>
        <NewDevBoardPage />
      </MemoryRouter>
    );

    // Wait for controllers to load
    await waitFor(() => {
      expect(controllersApi.getAll).toHaveBeenCalled();
    });

    // Open the controller dropdown
    fireEvent.click(screen.getByText('Select Controller'));
    
    // Click create new controller option
    fireEvent.click(screen.getByText('+ Create New Controller'));
    
    // Fill out the controller form
    fireEvent.change(screen.getByLabelText(/Controller Name/i), {
      target: { value: 'New Controller' }
    });
    
    // Submit the new controller form
    fireEvent.click(screen.getByRole('button', { name: /Create Controller/i }));
    
    // Check that error message is displayed
    await waitFor(() => {
      expect(screen.getByText(/Failed to create controller/i)).toBeInTheDocument();
    });
  });
}); 