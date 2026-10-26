package dev.portablevfx.protocol;

/** Immutable orthonormal, right-handed world basis; columns are local right, up, forward. */
public record EffectBasis(float rightX, float rightY, float rightZ,
        float upX, float upY, float upZ, float forwardX, float forwardY, float forwardZ) {
    public EffectBasis {
        float[] values = {rightX, rightY, rightZ, upX, upY, upZ, forwardX, forwardY, forwardZ};
        for (float value : values) {
            if (!Float.isFinite(value) || Math.abs(value) > 1.00001f)
                throw new IllegalArgumentException("basis components must be finite unit-vector components");
        }
        for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) {
            double dot = 0;
            for (int k = 0; k < 3; k++) dot += (double) values[a * 3 + k] * values[b * 3 + k];
            if (Math.abs(dot - (a == b ? 1 : 0)) > .00001)
                throw new IllegalArgumentException("basis must be orthonormal");
        }
        double determinant = ((double) rightY * upZ - (double) rightZ * upY) * forwardX
                + ((double) rightZ * upX - (double) rightX * upZ) * forwardY
                + ((double) rightX * upY - (double) rightY * upX) * forwardZ;
        if (determinant < .99999) throw new IllegalArgumentException("basis must be right-handed");
    }

    public static EffectBasis identity() { return new EffectBasis(1,0,0, 0,1,0, 0,0,1); }
    public float[] toArray() { return new float[]{rightX,rightY,rightZ,upX,upY,upZ,forwardX,forwardY,forwardZ}; }
    public static EffectBasis projectile(double x, double y, double z) {
        double[] forward = unit(x,y,z), up = Math.abs(forward[1]) < .999 ? new double[]{0,1,0} : new double[]{0,0,1};
        double[] right = unit(cross(up, forward));
        return pack(right, cross(forward,right), forward);
    }
    /** Local +Y follows the surface normal; +Z follows projected incoming direction. */
    public static EffectBasis impact(double nx, double ny, double nz, double dx, double dy, double dz) {
        double[] up = unit(nx,ny,nz), direction = unit(dx,dy,dz);
        double d = dot(direction,up);
        double[] forward = {direction[0]-up[0]*d, direction[1]-up[1]*d, direction[2]-up[2]*d};
        if (dot(forward,forward) < 1e-12) {
            double[] reference = Math.abs(up[1]) < .9 ? new double[]{0,1,0} : new double[]{0,0,1};
            double projection = dot(reference,up);
            forward = new double[]{reference[0]-up[0]*projection,reference[1]-up[1]*projection,reference[2]-up[2]*projection};
        }
        forward = unit(forward);
        return pack(unit(cross(up,forward)),up,forward);
    }
    private static EffectBasis pack(double[] r,double[] u,double[] f) {
        return new EffectBasis((float)r[0],(float)r[1],(float)r[2],(float)u[0],(float)u[1],(float)u[2],(float)f[0],(float)f[1],(float)f[2]);
    }
    private static double[] unit(double... vector) {
        for (double value : vector) if (!Double.isFinite(value)) throw new IllegalArgumentException("direction must be finite");
        double max = Math.max(Math.abs(vector[0]),Math.max(Math.abs(vector[1]),Math.abs(vector[2])));
        if (max < 1e-10) throw new IllegalArgumentException("direction must be nonzero");
        double x=vector[0]/max, y=vector[1]/max, z=vector[2]/max, length=Math.sqrt(x*x+y*y+z*z);
        return new double[]{x/length,y/length,z/length};
    }
    private static double dot(double[] a,double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static double[] cross(double[] a,double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]}; }
}
