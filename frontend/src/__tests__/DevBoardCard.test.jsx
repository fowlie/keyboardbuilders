import React from 'react';
import { render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import DevBoardCard from '../components/DevBoardCard';
import '@testing-library/jest-dom';

// Wrap component in Router since it uses Link
const renderWithRouter = (ui) => {
  return render(<BrowserRouter>{ui}</BrowserRouter>);
};

describe('DevBoardCard Component', () => {
  const mockController = {
    id: 1,
    name: 'Pro Micro',
    manufacturer: 'SparkFun',
    chipset: 'ATmega32U4'
  };

  const mockDevBoard = {
    id: 1,
    name: 'Elite-C',
    controller: mockController,
    wireless: false,
    url: 'https://example.com/elite-c'
  };

  it('renders correctly with all dev board data', () => {
    renderWithRouter(<DevBoardCard devBoard={mockDevBoard} />);
    
    expect(screen.getByText('Elite-C')).toBeInTheDocument();
    expect(screen.getByText('Wired')).toBeInTheDocument();
    expect(screen.getByText(/Controller:/i)).toBeInTheDocument();
    expect(screen.getByText('Pro Micro')).toBeInTheDocument();
    expect(screen.getByText(/Manufacturer: SparkFun/i)).toBeInTheDocument();
    expect(screen.getByText(/Chipset: ATmega32U4/i)).toBeInTheDocument();
    expect(screen.getByText('More Info')).toBeInTheDocument();
  });

  it('renders correctly with wireless dev board', () => {
    const wirelessDevBoard = {
      ...mockDevBoard,
      wireless: true
    };
    
    renderWithRouter(<DevBoardCard devBoard={wirelessDevBoard} />);
    expect(screen.getByText('Wireless')).toBeInTheDocument();
  });

  it('renders correctly with no controller', () => {
    const noControllerDevBoard = {
      ...mockDevBoard,
      controller: null
    };
    
    renderWithRouter(<DevBoardCard devBoard={noControllerDevBoard} />);
    expect(screen.queryByText(/Controller:/i)).not.toBeInTheDocument();
  });

  it('renders nothing when dev board is null', () => {
    const { container } = render(<DevBoardCard devBoard={null} />);
    expect(container).toBeEmptyDOMElement();
  });
}); 