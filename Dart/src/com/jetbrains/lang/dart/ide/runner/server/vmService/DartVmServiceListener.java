package com.jetbrains.lang.dart.ide.runner.server.vmService;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.jetbrains.lang.dart.ide.runner.server.vmService.frame.DartVmServiceSuspendContext;
import org.dartlang.vm.service.VmServiceListener;
import org.dartlang.vm.service.element.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DartVmServiceListener implements VmServiceListener {
  private static final Logger LOG = Logger.getInstance(DartVmServiceListener.class.getName());

  @NotNull private final DartVmServiceDebugProcess myDebugProcess;
  @NotNull private final DartVmServiceBreakpointHandler myBreakpointHandler;
  @Nullable private XSourcePosition myLatestSourcePosition;

  public DartVmServiceListener(@NotNull final DartVmServiceDebugProcess debugProcess,
                               @NotNull final DartVmServiceBreakpointHandler breakpointHandler) {
    myDebugProcess = debugProcess;
    myBreakpointHandler = breakpointHandler;
  }

  @Override
  public void received(@NotNull final String streamId, @NotNull final Event event) {
    switch (event.getKind()) {
      case BreakpointAdded:
        break;
      case BreakpointRemoved:
        break;
      case BreakpointResolved:
        myBreakpointHandler.breakpointResolved(event.getBreakpoint());
        break;
      case GC:
        break;
      case IsolateExit:
        myDebugProcess.isolateExit(event.getIsolate());
        break;
      case IsolateRunnable:
        break;
      case IsolateStart:
        break;
      case IsolateUpdate:
        break;
      case PauseBreakpoint:
      case PauseException:
      case PauseInterrupted:
        myDebugProcess.isolateSuspended(event.getIsolate());

        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
          @Override
          public void run() {
            final ElementList<Breakpoint> breakpoints = event.getKind() == EventKind.PauseBreakpoint ? event.getPauseBreakpoints() : null;
            final InstanceRef exception = event.getKind() == EventKind.PauseException ? event.getException() : null;
            onIsolatePaused(event.getIsolate(), breakpoints, exception, event.getTopFrame());
          }
        });
        break;
      case PauseExit:
        break;
      case PauseStart:
        myDebugProcess.getVmServiceWrapper().handleIsolatePausedOnStart(event.getIsolate());
        break;
      case Resume:
        myDebugProcess.isolateResumed(event.getIsolate());
        break;
      case VMUpdate:
        break;
      case WriteEvent:
        break;
      case Unknown:
        break;
    }
  }

  private void onIsolatePaused(@NotNull final IsolateRef isolateRef,
                               @Nullable final ElementList<Breakpoint> vmBreakpoints,
                               @Nullable final InstanceRef exception,
                               @Nullable final Frame topFrame) {
    if (topFrame == null) {
      myDebugProcess.getSession().positionReached(new XSuspendContext() {
      });
      return;
    }

    final DartVmServiceSuspendContext suspendContext = new DartVmServiceSuspendContext(myDebugProcess, isolateRef, topFrame, exception);
    final XStackFrame xTopFrame = suspendContext.getActiveExecutionStack().getTopFrame();
    final XSourcePosition sourcePosition = xTopFrame == null ? null : xTopFrame.getSourcePosition();

    if (vmBreakpoints == null || vmBreakpoints.isEmpty()) {
      final StepOption latestStep = myDebugProcess.getVmServiceWrapper().getLatestStep();

      if (latestStep != null && equalSourcePositions(myLatestSourcePosition, sourcePosition)) {
        // continue stepping to change current line
        myDebugProcess.getVmServiceWrapper().resumeIsolate(isolateRef.getId(), latestStep);
      }
      else {
        myLatestSourcePosition = sourcePosition;
        myDebugProcess.getSession().positionReached(suspendContext);
      }
    }
    else {
      if (vmBreakpoints.size() > 1) {
        // Shouldn't happen. IDE doesn't allow to set 2 breakpoints on one line.
        LOG.error(vmBreakpoints.size() + " breakpoints hit in one shot.");
      }

      final XLineBreakpoint<XBreakpointProperties> xBreakpoint = myBreakpointHandler.getXBreakpoint(vmBreakpoints.get(0));
      myLatestSourcePosition = sourcePosition;
      myDebugProcess.getSession().breakpointReached(xBreakpoint, null, suspendContext);
    }
  }

  private static boolean equalSourcePositions(@Nullable final XSourcePosition position1, @Nullable final XSourcePosition position2) {
    return position1 != null &&
           position2 != null &&
           position1.getFile().equals(position2.getFile()) &&
           position1.getLine() == position2.getLine();
  }
}
