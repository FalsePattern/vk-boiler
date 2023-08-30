package com.github.knokko.boiler.cull;

import org.joml.Vector3f;

import static org.joml.Math.*;

public class FrustumCuller {

    private static Vector3f cross(Vector3f left, Vector3f right) {
        return left.cross(right, new Vector3f());
    }

    private static Vector3f add(Vector3f left, Vector3f right) {
        return left.add(right, new Vector3f());
    }

    private static Vector3f sub(Vector3f left, Vector3f right) {
        return left.sub(right, new Vector3f());
    }

    private static Vector3f scale(Vector3f vector, float scalar) {
        return vector.mul(scalar, new Vector3f());
    }

    static float getSignedDistanceToPlane(Plane plane, float x, float y, float z) {
        return plane.normal.dot(x, y, z) - plane.distance;
    }

    static boolean isOnForwardPlane(AABB box, Plane plane) {
        float projectionIntervalRadius = box.halfWidth() * abs(plane.normal.x) + box.halfHeight() * abs(plane.normal.y)
                + box.halfDepth() * abs(plane.normal.z);
        return -projectionIntervalRadius <= getSignedDistanceToPlane(plane, box.centerX(), box.centerY(), box.centerZ());
    }

    private static Vector3f forwardVector(float yaw, float pitch) {
        float yawDamper = abs(cos(toRadians(pitch)));
        return new Vector3f(sin(toRadians(yaw)) * yawDamper, sin(toRadians(pitch)), -cos(toRadians(yaw)) * yawDamper);
    }

    private final Plane topFace, bottomFace, rightFace, leftFace, farFace, nearFace;

    public FrustumCuller(
            Vector3f cameraPosition, float yaw, float pitch,
            float aspectRatio, float fov, float nearPlane, float farPlane
    ) {
        this(cameraPosition, forwardVector(yaw, pitch), forwardVector(yaw, pitch + 90f), aspectRatio, fov, nearPlane, farPlane);
    }

    public FrustumCuller(
            Vector3f cameraPosition, Vector3f frontDirection, Vector3f upDirection,
            float aspectRatio, float fov, float nearPlane, float farPlane
    ) {
        float halfVerticalSide = farPlane * tan(toRadians(fov) * 0.5f);
        float halfHorizontalSide = halfVerticalSide * aspectRatio;
        Vector3f frontTimesFar = scale(frontDirection, farPlane);
        Vector3f rightDirection = cross(frontDirection, upDirection);
        Vector3f leftDirection = scale(rightDirection, -1f);
        Vector3f downDirection = scale(upDirection, -1f);

        this.nearFace = new Plane(add(cameraPosition, scale(frontDirection, nearPlane)), frontDirection);
        this.farFace = new Plane(frontTimesFar, scale(frontDirection, -1f));

        this.rightFace = new Plane(cameraPosition,
                cross(upDirection, sub(frontTimesFar, scale(leftDirection, halfHorizontalSide)))
        );
        this.leftFace = new Plane(cameraPosition,
                cross(add(frontTimesFar, scale(leftDirection, halfHorizontalSide)), upDirection)
        );

        this.topFace = new Plane(cameraPosition,
                cross(sub(frontTimesFar, scale(downDirection, halfVerticalSide)), rightDirection)
        );
        this.bottomFace = new Plane(cameraPosition,
                cross(rightDirection, add(frontTimesFar, scale(downDirection, halfVerticalSide)))
        );
    }

    public boolean shouldCullAABB(AABB box) {
        return !(isOnForwardPlane(box, nearFace) && isOnForwardPlane(box, farFace)
                && isOnForwardPlane(box, leftFace) && isOnForwardPlane(box, rightFace)
                && isOnForwardPlane(box, bottomFace) && isOnForwardPlane(box, topFace));
    }

    public record AABB(
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ
    ) {
        float halfWidth() {
            return (maxX - minX) * 0.5f;
        }

        float halfHeight() {
            return (maxY - minY) * 0.5f;
        }

        float halfDepth() {
            return (maxZ - minZ) * 0.5f;
        }

        float centerX() {
            return (minX + maxX) * 0.5f;
        }

        float centerY() {
            return (minY + maxY) * 0.5f;
        }

        float centerZ() {
            return (minZ + maxZ) * 0.5f;
        }
    }

    record Plane(Vector3f normal, float distance) {
        public Plane(Vector3f position, Vector3f normal) {
            this(normal.normalize(new Vector3f()), position.dot(normal.normalize(new Vector3f())));
        }
    }
}
